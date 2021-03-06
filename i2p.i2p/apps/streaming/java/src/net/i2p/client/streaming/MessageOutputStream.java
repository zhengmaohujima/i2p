package net.i2p.client.streaming;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;

import net.i2p.I2PAppContext;
import net.i2p.data.ByteArray;
import net.i2p.util.ByteCache;
import net.i2p.util.Log;
import net.i2p.util.SimpleTimer2;

/**
 * A stream that we can shove data into that fires off those bytes
 * on flush or when the buffer is full.  It also blocks according
 * to the data receiver's needs.
 */
class MessageOutputStream extends OutputStream {
    private final I2PAppContext _context;
    private final Log _log;
    private byte _buf[];
    private int _valid;
    private final Object _dataLock;
    private DataReceiver _dataReceiver;
    private IOException _streamError;
    private boolean _closed;
    private long _written;
    private int _writeTimeout;
    private ByteCache _dataCache;
    private final Flusher _flusher;
    private long _lastFlushed;
    private long _lastBuffered;
    /** if we enqueue data but don't flush it in this period, flush it passively */
    private int _passiveFlushDelay;
    /** 
     * if we are changing the buffer size during operation, set this to the new 
     * buffer size, and next time we are flushing, update the _buf array to the new 
     * size
     */
    private volatile int _nextBufferSize;
    // rate calc helpers
    private long _sendPeriodBeginTime;
    private long _sendPeriodBytes;
    private int _sendBps;
    
    /**
     *  Since this is less than i2ptunnel's i2p.streaming.connectDelay default of 1000,
     *  we only wait 250 at the start. Guess that's ok, 1000 is too long anyway.
     */
    private static final int DEFAULT_PASSIVE_FLUSH_DELAY = 250;

    public MessageOutputStream(I2PAppContext ctx, DataReceiver receiver) {
        this(ctx, receiver, Packet.MAX_PAYLOAD_SIZE);
    }
    public MessageOutputStream(I2PAppContext ctx, DataReceiver receiver, int bufSize) {
        this(ctx, receiver, bufSize, DEFAULT_PASSIVE_FLUSH_DELAY);
    }
    public MessageOutputStream(I2PAppContext ctx, DataReceiver receiver, int bufSize, int passiveFlushDelay) {
        super();
        _dataCache = ByteCache.getInstance(128, bufSize);
        _context = ctx;
        _log = ctx.logManager().getLog(MessageOutputStream.class);
        _buf = _dataCache.acquire().getData(); // new byte[bufSize];
        _dataReceiver = receiver;
        _dataLock = new Object();
        _writeTimeout = -1;
        _passiveFlushDelay = passiveFlushDelay;
        _nextBufferSize = -1;
        _sendPeriodBeginTime = ctx.clock().now();
        _context.statManager().createRateStat("stream.sendBps", "How fast we pump data through the stream", "Stream", new long[] { 60*1000, 5*60*1000, 60*60*1000 });
        _flusher = new Flusher();
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("MessageOutputStream created");
    }
    
    public void setWriteTimeout(int ms) { 
        if (_log.shouldLog(Log.INFO))
            _log.info("Changing write timeout from " + _writeTimeout + " to " + ms);

        _writeTimeout = ms; 
    }
    public int getWriteTimeout() { return _writeTimeout; }
    public void setBufferSize(int size) { _nextBufferSize = size; }
    
	@Override
    public void write(byte b[]) throws IOException {
        write(b, 0, b.length);
    }
    
	@Override
    public void write(byte b[], int off, int len) throws IOException {
        if (_closed) throw new IOException("Already closed");
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("write(b[], " + off + ", " + len + ") ");
        int cur = off;
        int remaining = len;
        long begin = _context.clock().now();
        while (remaining > 0) {
            WriteStatus ws = null;
            if (_closed) throw new IOException("closed underneath us");
            // we do any waiting outside the synchronized() block because we
            // want to allow other threads to flushAvailable() whenever they want.  
            // this is the only method that *adds* to the _buf, and all 
            // code that reads from it is synchronized
            synchronized (_dataLock) {
                if (_buf == null) throw new IOException("closed (buffer went away)");
                if (_valid + remaining < _buf.length) {
                    // simply buffer the data, no flush
                    System.arraycopy(b, cur, _buf, _valid, remaining);
                    _valid += remaining;
                    cur += remaining;
                    _written += remaining;
                    remaining = 0;
                    _lastBuffered = _context.clock().now();
                    if (_passiveFlushDelay > 0) {
                        _flusher.enqueue();
                    }
                } else {
                    // buffer whatever we can fit then flush,
                    // repeating until we've pushed all of the
                    // data through
                    int toWrite = _buf.length - _valid;
                    System.arraycopy(b, cur, _buf, _valid, toWrite);
                    remaining -= toWrite;
                    cur += toWrite;
                    _valid = _buf.length;
                    // avoid NPE from race with destroy()
                    DataReceiver rcvr = _dataReceiver;
                    if (rcvr == null) {
                        throwAnyError();
                        return;
                    }
                    if (_log.shouldLog(Log.INFO))
                        _log.info("write() direct valid = " + _valid);
                    ws = rcvr.writeData(_buf, 0, _valid);
                    _written += _valid;
                    _valid = 0;                       
                    throwAnyError();
                    _lastFlushed = _context.clock().now();
                    
                    locked_updateBufferSize();
                }
            }
            if (ws != null) {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Waiting " + _writeTimeout + "ms for accept of " + ws);
                // ok, we've actually added a new packet - lets wait until
                // its accepted into the queue before moving on (so that we 
                // dont fill our buffer instantly)
                ws.waitForAccept(_writeTimeout);
                if (!ws.writeAccepted()) {
                    if (_log.shouldLog(Log.WARN))
                        _log.warn("Write not accepted of " + ws);
                    if (_writeTimeout > 0)
                        throw new InterruptedIOException("Write not accepted within timeout: " + ws);
                    else
                        throw new IOException("Write not accepted into the queue: " + ws);
                } else {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("After waitForAccept of " + ws);
                }
            } else {
                if (_log.shouldLog(Log.INFO))
                    _log.info("Queued " + len + " without sending to the receiver");
            }
        }
        long elapsed = _context.clock().now() - begin;
        if ( (elapsed > 10*1000) && (_log.shouldLog(Log.INFO)) )
            _log.info("wtf, took " + elapsed + "ms to write to the stream?", new Exception("foo"));
        throwAnyError();
        updateBps(len);
    }
    
    private void updateBps(int len) {
        long now = _context.clock().now();
        int periods = (int)Math.floor((now - _sendPeriodBeginTime) / 1000d);
        if (periods > 0) {
            // first term decays on slow transmission
            _sendBps = (int)((0.9f*((float)_sendBps/(float)periods)) + (0.1f*((float)_sendPeriodBytes/(float)periods)));
            _sendPeriodBytes = len;
            _sendPeriodBeginTime = now;
            _context.statManager().addRateData("stream.sendBps", _sendBps, 0);
        } else {
            _sendPeriodBytes += len;
        }
    }
    
    public void write(int b) throws IOException {
        write(new byte[] { (byte)b }, 0, 1);
        throwAnyError();
    }
    
    /**
     * If the other side requested we shrink our buffer, do so.
     *
     */
    private final void locked_updateBufferSize() {
        int size = _nextBufferSize;
        if (size > 0) {
            // update the buffer size to the requested amount
            _dataCache.release(new ByteArray(_buf));
            _dataCache = ByteCache.getInstance(128, size);
            ByteArray ba = _dataCache.acquire();
            _buf = ba.getData();
            _nextBufferSize = -1;
        }
    }
    
    /**
     * Flush data that has been enqued but not flushed after a certain 
     * period of inactivity
     */
    private class Flusher extends SimpleTimer2.TimedEvent {
        private boolean _enqueued;
        public Flusher() { 
            super(RetransmissionTimer.getInstance());
        }
        public void enqueue() {
            // no need to be overly worried about duplicates - it would just 
            // push it further out
            if (!_enqueued) {
                // Maybe we could just use schedule() here - or even SimpleScheduler - not sure...
                // To be safe, use forceReschedule() so we don't get lots of duplicates
                // We've seen the queue blow up before, maybe it was this before the rewrite...
                // So perhaps it IS wise to be "overly worried" ...
                forceReschedule(_passiveFlushDelay);
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("Enqueueing the flusher for " + _passiveFlushDelay + "ms out");
            } else {
                if (_log.shouldLog(Log.DEBUG))
                    _log.debug("NOT enqueing the flusher");
            }
            _enqueued = true;
        }
        public void timeReached() {
            _enqueued = false;
            DataReceiver rec = _dataReceiver;
            long timeLeft = (_lastBuffered + _passiveFlushDelay - _context.clock().now());
            if (_log.shouldLog(Log.DEBUG))
                _log.debug("flusher time reached: left = " + timeLeft);
            if (timeLeft > 0)
                enqueue();
            else if ( (rec != null) && (rec.writeInProcess()) )
                enqueue(); // don't passive flush if there is a write being done (unacked outbound)
            else
                doFlush();
        }
        
        private void doFlush() {
            boolean sent = false;
            WriteStatus ws = null;
            synchronized (_dataLock) {
                long flushTime = _lastBuffered + _passiveFlushDelay;
                if ( (_valid > 0) && (flushTime <= _context.clock().now()) ) {
                    if (_log.shouldLog(Log.INFO))
                        _log.info("doFlush() valid = " + _valid);
                    // avoid NPE from race with destroy()
                    DataReceiver rcvr = _dataReceiver;
                    if ( (_buf != null) && (rcvr != null) ) {
                        ws = rcvr.writeData(_buf, 0, _valid);
                        _written += _valid;
                        _valid = 0;
                        _lastFlushed = _context.clock().now();
                        locked_updateBufferSize();
                        _dataLock.notifyAll();
                        sent = true;
                    }
                } else {
                    if (_log.shouldLog(Log.INFO) && _valid > 0)
                        _log.info("doFlush() rejected... valid = " + _valid);
                }
            }
            // ignore the ws
            if (sent && _log.shouldLog(Log.INFO)) 
                _log.info("Passive flush of " + ws);
        }
    }
    
    /** 
     * Flush the data already queued up, blocking only if the outbound
     * window is full.
     *
     * Prior to 0.8.1, this blocked until "delivered".
     * "Delivered" meant "received an ACK from the far end",
     * which is not the commom implementation of flush(), and really hurt the
     * performance of i2psnark, which flush()ed frequently.
     * Calling flush() would cause a complete window stall.
     *
     * As of 0.8.1, only wait for accept into the streaming output queue.
     * This will speed up snark significantly, and allow us to flush()
     * the initial data in I2PTunnelRunner, saving 250 ms.
     *
     * @throws IOException if the write fails
     */
	@Override
    public void flush() throws IOException {
     /* @throws InterruptedIOException if the write times out
      * Documented here, but doesn't belong in the javadoc. 
      */
        flush(true);
    }

    /**
     *  @param wait_for_accept_only see discussion in close() code
     *  @@since 0.8.1
     */
    private void flush(boolean wait_for_accept_only) throws IOException {
        long begin = _context.clock().now();
        WriteStatus ws = null;
        if (_log.shouldLog(Log.INFO) && _valid > 0)
            _log.info("flush() valid = " + _valid);

        // avoid NPE from race with destroy()
        DataReceiver rcvr = _dataReceiver;
        synchronized (_dataLock) {
            if (_buf == null) {
                _dataLock.notifyAll();
                throw new IOException("closed (buffer went away)");
            }

            if (rcvr == null) {
                _dataLock.notifyAll();
                throwAnyError();
                return;
            }
            // if valid == 0 return ??? - no, this could flush a CLOSE packet too.

            // Yes, flush here, inside the data lock, and do all the waitForCompletion() stuff below
            // (disabled)
            if (!wait_for_accept_only) {
                ws = rcvr.writeData(_buf, 0, _valid);
                _written += _valid;
                _valid = 0;
                locked_updateBufferSize();
                _lastFlushed = _context.clock().now();
                _dataLock.notifyAll();
            }
        }
        
        // Skip all the waitForCompletion() stuff below, which is insanity, as of 0.8.1
        // must do this outside the data lock
        if (wait_for_accept_only) {
            flushAvailable(rcvr, true);
            return;
        }

        // Wait a loooooong time, until we have the ACK
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("before waiting " + _writeTimeout + "ms for completion of " + ws);
        if (_closed && 
            ( (_writeTimeout > Connection.DISCONNECT_TIMEOUT) ||
              (_writeTimeout <= 0) ) )
            ws.waitForCompletion(Connection.DISCONNECT_TIMEOUT);
        else if ( (_writeTimeout <= 0) || (_writeTimeout > Connection.DISCONNECT_TIMEOUT) )
            ws.waitForCompletion(Connection.DISCONNECT_TIMEOUT);
        else
            ws.waitForCompletion(_writeTimeout);
        if (_log.shouldLog(Log.DEBUG))
            _log.debug("after waiting " + _writeTimeout + "ms for completion of " + ws);
        if (ws.writeFailed() && (_writeTimeout > 0) )
            throw new InterruptedIOException("Timed out during write");
        else if (ws.writeFailed())
            throw new IOException("Write failed");
        
        long elapsed = _context.clock().now() - begin;
        if ( (elapsed > 10*1000) && (_log.shouldLog(Log.DEBUG)) )
            _log.debug("wtf, took " + elapsed + "ms to flush the stream?\n" + ws, new Exception("bar"));
        throwAnyError();
    }
    
    /**
     *  This does a flush, and BLOCKS until
     *  the CLOSE packet is acked.
     */
    @Override
    public void close() throws IOException {
        if (_closed) {
            synchronized (_dataLock) { _dataLock.notifyAll(); }
            return;
        }
        // setting _closed before flush() will force flush() to send a CLOSE packet
        _closed = true;

        // In 0.8.1 we rewrote flush() to only wait for accept into the window,
        // not "completion" (i.e. ack from the far end).
        // Unfortunately, that broke close(), at least in i2ptunnel HTTPClient.
        // Symptom was premature close, i.e. incomplete pages and images.
        // Possible cause - I2PTunnelRunner code? or the code here that follows flush()?
        // It seems like we shouldn't have to wait for the far-end ACK for a close packet,
        // should we? To be researched further.
        // false -> wait for completion, not just accept.
        flush(false);
        _log.debug("Output stream closed after writing " + _written);
        ByteArray ba = null;
        synchronized (_dataLock) {
            if (_buf != null) {
                ba = new ByteArray(_buf);
                _buf = null;
                _valid = 0;
                locked_updateBufferSize();
            }
            _dataLock.notifyAll();
        }
        if (ba != null) {
            _dataCache.release(ba);
        }
    }

    /**
     *  nonblocking close -
     *  Use outside of this package is deprecated, should be made package local
     */
    public void closeInternal() {
        _closed = true;
        if (_streamError == null)
            _streamError = new IOException("Closed internally");
        clearData(true);
    }
    
    private void clearData(boolean shouldFlush) {
        ByteArray ba = null;
        if (_log.shouldLog(Log.INFO) && _valid > 0)
            _log.info("clearData() valid = " + _valid);

        // avoid NPE from race with destroy()
        DataReceiver rcvr = _dataReceiver;
        synchronized (_dataLock) {
            // flush any data, but don't wait for it
            if ( (rcvr != null) && (_valid > 0) && shouldFlush)
                rcvr.writeData(_buf, 0, _valid);
            _written += _valid;
            _valid = 0;
            
            if (_buf != null) {
                ba = new ByteArray(_buf);
                _buf = null;
                _valid = 0;
            }
            _lastFlushed = _context.clock().now();
            _dataLock.notifyAll();
        }
        if (ba != null) {
            _dataCache.release(ba);
        }
    }
    
    public boolean getClosed() { return _closed; }
    
    private void throwAnyError() throws IOException {
        if (_streamError != null) {
            IOException ioe = _streamError;
            _streamError = null;
            throw ioe;
        }
    }
    
    void streamErrorOccurred(IOException ioe) {
        if (_streamError == null)
            _streamError = ioe;
        clearData(false);
    }
    
    /** 
     * called whenever the engine wants to push more data to the
     * peer
     */
    void flushAvailable(DataReceiver target) throws IOException {
        flushAvailable(target, true);
    }
    void flushAvailable(DataReceiver target, boolean blocking) throws IOException {
        WriteStatus ws = null;
        long before = System.currentTimeMillis();
        if (_log.shouldLog(Log.INFO) && _valid > 0)
            _log.info("flushAvailable() valid = " + _valid);
        synchronized (_dataLock) {
            // if valid == 0 return ??? - no, this could flush a CLOSE packet too.

            // _buf may be null, but the data receiver can handle that just fine,
            // deciding whether or not to send a packet
            ws = target.writeData(_buf, 0, _valid);
            _written += _valid;
            _valid = 0;
            locked_updateBufferSize();
            _dataLock.notifyAll();
            _lastFlushed = _context.clock().now();
        }
        long afterBuild = System.currentTimeMillis();
        if ( (afterBuild - before > 1000) && (_log.shouldLog(Log.DEBUG)) )
            _log.debug("Took " + (afterBuild-before) + "ms to build a packet?  " + ws);
        
        if (blocking && ws != null) {
            ws.waitForAccept(_writeTimeout);
            if (ws.writeFailed())
                throw new IOException("Flush available failed");
            else if (!ws.writeAccepted())
                throw new InterruptedIOException("Flush available timed out (" + _writeTimeout + "ms)");
        }
        long afterAccept = System.currentTimeMillis();
        if ( (afterAccept - afterBuild > 1000) && (_log.shouldLog(Log.DEBUG)) )
            _log.debug("Took " + (afterAccept-afterBuild) + "ms to accept a packet? " + ws);
        return;
    }
    
    void destroy() {
        _dataReceiver = null;
        synchronized (_dataLock) {
            _closed = true;
            _dataLock.notifyAll();
        }
    }
    
    /** Define a component to receive data flushed from this stream */
    public interface DataReceiver {
        /**
         * Nonblocking write
         */
        public WriteStatus writeData(byte buf[], int off, int size);
        public boolean writeInProcess();
    }
    
    /** Define a way to detect the status of a write */
    public interface WriteStatus {
        /**
         * Wait until the data written either fails or succeeds.
         * Success means an ACK FROM THE FAR END.
         * @param maxWaitMs -1 = forever
         */
        public void waitForCompletion(int maxWaitMs);

        /** 
         * Wait until the data written is accepted into the outbound pool,
         * (i.e. the outbound window is not full)
         * which we throttle rather than accept arbitrary data and queue 
         * @param maxWaitMs -1 = forever
         */
        public void waitForAccept(int maxWaitMs);

        /** the write was accepted.  aka did the socket not close? */
        public boolean writeAccepted();
        /** did the write fail?  */
        public boolean writeFailed();
        /** did the write succeed? */
        public boolean writeSuccessful();
    }
}
