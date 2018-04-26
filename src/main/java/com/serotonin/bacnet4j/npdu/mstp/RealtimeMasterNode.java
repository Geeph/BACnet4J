/**
 * Copyright (C) 2018 Infinite Automation Software. All rights reserved.
 */
package com.serotonin.bacnet4j.npdu.mstp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

import org.apache.commons.lang3.StringUtils;

import com.serotonin.bacnet4j.npdu.mstp.realtime.RealtimeDriver;
import com.serotonin.bacnet4j.transport.Transport;
import com.serotonin.bacnet4j.util.sero.StreamUtils;
import com.serotonin.bacnet4j.util.sero.ThreadUtils;


/**
 * MS/TP Master node using a real-time serial driver 
 *   installed in the linux Kernel.
 * 
 * @author Terry Packer
 */
public class RealtimeMasterNode extends MasterNode {
    
    private int portFileDescriptor = -1;
    private final byte thisStation;
    private final RealtimeDriver driver;
    private final int baud;
    
    public RealtimeMasterNode(final String portId, final Properties driverProperties, final byte thisStation, final int retryCount, final int baud) throws IllegalArgumentException {
        super(portId, null, null, (byte)0xFF, retryCount);
        this.thisStation = thisStation;
        this.baud = baud;
        this.driver = new RealtimeDriver(driverProperties);
    }
    
    @Override
    protected void validate(final int retryCount) {
        this.retryCount = retryCount;
        nextStation = thisStation;
        pollStation = thisStation;
        tokenCount = Constants.POLL;
        soleMaster = false;
        state = MasterNodeState.idle;
    }
    
    @Override
    public void setMaxMaster(final int maxMaster) {
        super.setMaxMaster(maxMaster);
        if(portFileDescriptor > 0)
            this.driver.setMaxMaster(portFileDescriptor, (byte)maxMaster);
    }

    @Override
    public void setMaxInfoFrames(final int maxInfoFrames) {
        super.setMaxInfoFrames(maxInfoFrames);
        if(portFileDescriptor > 0)
            this.driver.setMaxInfoFrames(portFileDescriptor, (byte)maxInfoFrames);
    }

    @Override
    public void setUsageTimeout(final int usageTimeout) {
        super.setUsageTimeout(usageTimeout);
        if(portFileDescriptor > 0)
            this.driver.setTUsage(portFileDescriptor, (byte)usageTimeout);
    }
    
    @Override
    public void initialize(final Transport transport) throws Exception {
        portFileDescriptor = driver.setupPort(portId, baud);
        in = new RealtimeDriverInputStream(driver, portFileDescriptor);
        out = new RealtimeDriverOutputStream(driver, portFileDescriptor);
        driver.setMac(portFileDescriptor, thisStation);
        driver.setMaxMaster(portFileDescriptor, (byte)this.maxMaster);
        driver.setTUsage(portFileDescriptor, (byte)this.usageTimeout);
        driver.setMaxInfoFrames(portFileDescriptor, (byte)this.maxInfoFrames);
        
        super.initialize(transport);
    }
    
    @Override
    protected void doCycle() {
        System.out.println("State: " + state);
        readFrame();

        if (state == MasterNodeState.idle) {
            idle();
            state = MasterNodeState.useToken;
        }
        
        if(state == MasterNodeState.useToken)
            useToken();
        
        if(state == MasterNodeState.doneWithToken)
            state = MasterNodeState.idle;

//        if (state == MasterNodeState.waitForReply)
//            waitForReply();

        if (state == MasterNodeState.answerDataRequest)
            answerDataRequest();
        
        //TODO Remove when done debugging
        try {Thread.sleep(500);}catch(InterruptedException e) {}
    }
    
    @Override
    protected void readFrame() {
        readInputStream();
        
        if (receiveError) {
            // EatAnError
            receiveError = false;
            eventCount++;
            activity = true;
        }
    }

    
    @Override
    protected void idle() {
        if (receivedInvalidFrame != null) {
            // ReceivedInvalidFrame
            if (LOG.isDebugEnabled())
                LOG.debug(thisStation + " idle:Received invalid frame: " + receivedInvalidFrame);
            receivedInvalidFrame = null;
            activity = true;
        } else if (receivedValidFrame) {
            frame();
            receivedValidFrame = false;
            activity = true;
        }
    }
    
    @Override
    protected void sendFrame(final Frame frame) {
        try {
            if (LOG.isTraceEnabled())
                LOG.trace(tracePrefix() + "out: " + frame);
            System.out.println("out: " + frame);
            
            // Header
            byte[] buffer = new byte[5 + frame.getLength()];
            int pos = 0;
            buffer[pos++] = frame.getFrameType().id;
            buffer[pos++] = frame.getDestinationAddress();
            buffer[pos++] = frame.getSourceAddress();
            buffer[pos++] = (byte)(frame.getLength() >> 8 & 0xff);
            buffer[pos++] = (byte)(frame.getLength() & 0xff);
            
           // out.write(frame.getFrameType().id & 0xff);
           // out.write(frame.getDestinationAddress() & 0xff);
           // out.write(frame.getSourceAddress() & 0xff);
           // out.write(frame.getLength() >> 8 & 0xff);
           // out.write(frame.getLength() & 0xff);
            //Driver doesn't want CRC out.write(sendHeaderCRC.getCrc(frame));
            bytesOut += 8;

            if (frame.getLength() > 0) {
                // Data
                //out.write(frame.getData());
                for(int i=0; i<frame.getLength(); i++)
                    buffer[pos++] = frame.getData()[i];
                //No CRC with Driver
                //final int crc = sendDataCRC.getCrc(frame);
                //out.write(crc & 0xff);
                //out.write(crc >> 8 & 0xff);
                bytesOut += frame.getLength(); // + 2;
            }
            out.write(buffer);
            //out.flush(); //Probably don't need it...
        } catch (final IOException e) {
            // Only write the same error message once. Prevents logs from getting filled up unnecessarily with repeated
            // error messages.
            if (!StringUtils.equals(e.getMessage(), lastWriteError)) {
                // NOTE: should anything else be informed of this?
                LOG.error("Error while sending frame", e);
                lastWriteError = e.getMessage();
            }
        }
    }
    
    @Override
    protected long silence() {
        return 0l;
    }

    
    @Override
    protected void readInputStream() {
        try {
            while((readCount = in.read(readArray)) > 0) {
                bytesIn += readCount;
                if (LOG.isTraceEnabled())
                    LOG.trace(tracePrefix() + "in: " + StreamUtils.dumpArrayHex(readArray, 0, readCount));
                inputBuffer.push(readArray, 0, readCount);
                eventCount += readCount;
                //Assume the driver supplies a full message every time
                //TODO Must we determine a frame type???? HOW?!!
                int pos = 0;
                frame.setSourceAddress(readArray[pos++]);
                byte[] data = new byte[readCount - 1];
                for(int i=0; i<readCount - 1; i++) {
                    data[i] = readArray[pos++];
                }
                frame.setData(data);
                receivedDataNoReply(frame);
                if(state == MasterNodeState.waitForReply)
                    state = MasterNodeState.idle;
                //receivedDataNeedingReply(frame);
            }
        } catch (final IOException e) {
            if (StringUtils.equals(e.getMessage(), "Stream closed."))
                throw new RuntimeException(e);
            if (LOG.isDebugEnabled())
                LOG.debug(thisStation + " Input stream listener exception", e);
            receiveError = true;
        }
    }
    
    @Override
    public void terminate() {
        super.terminate();
        if(portFileDescriptor > 0) {
            this.driver.close(portFileDescriptor);
        }
    }
    
    public static class RealtimeDriverInputStream extends InputStream {
        
        private final RealtimeDriver driver;
        private final int handle;
        
        public RealtimeDriverInputStream(RealtimeDriver driver, int handle) {
            this.driver = driver;
            this.handle = handle;
        }

        
        @Override
        public int read() throws IOException {
            byte[] inBuffer = new byte[1];
            while (true) {
                int read = driver.read(handle, inBuffer, 1);
                if (read < 1) {
                    ThreadUtils.sleep(20);
                    continue;
                }
                return inBuffer[0];
            }
        }
        
        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            if (len == 0) {
                return 0;
            }

            final byte[] buf = new byte[len];
            int length = driver.read(handle, buf, len);
            if(length > 0) {
                System.arraycopy(buf, 0, b, off, length);
                System.out.println("Recieved: " );
                for(int i=0; i<length; i++)
                    System.out.print(String.format("0x%02X", buf[i]) + " ");
                System.out.println();
            }
            return length;
        }
        
        @Override
        public int available() throws IOException {
            throw new IOException("available(): Unsupported operation.");
        }
    }
    
    public static class RealtimeDriverOutputStream extends OutputStream {
        
        private final RealtimeDriver driver;
        private final int handle;
        
        public RealtimeDriverOutputStream(RealtimeDriver driver, int handle) {
            this.driver = driver;
            this.handle = handle;
        }

        @Override
        public void write(final int b) throws IOException {
            driver.write(handle, new byte[] {(byte)b}, 1);
        }

        @Override
        public void write(final byte[] buffer) throws IOException {
            driver.write(handle, buffer, buffer.length);
        }
        /* (non-Javadoc)
         * @see java.io.OutputStream#flush()
         */
        @Override
        public void flush() throws IOException {
            driver.flush(handle);
        }
    }
}
