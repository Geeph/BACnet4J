/**
 * Copyright (C) 2018 Infinite Automation Software. All rights reserved.
 */
package com.serotonin.bacnet4j.npdu.mstp.realtime;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

import com.serotonin.bacnet4j.LocalDevice;
import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.event.DeviceEventAdapter;
import com.serotonin.bacnet4j.event.ExceptionListener;
import com.serotonin.bacnet4j.npdu.Network;
import com.serotonin.bacnet4j.npdu.mstp.MstpNetwork;
import com.serotonin.bacnet4j.npdu.mstp.RealtimeMasterNode;
import com.serotonin.bacnet4j.service.unconfirmed.WhoIsRequest;
import com.serotonin.bacnet4j.transport.DefaultTransport;
import com.serotonin.bacnet4j.transport.Transport;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.CharacterString;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;
import com.serotonin.bacnet4j.util.sero.ByteQueue;
import com.serotonin.bacnet4j.util.sero.ThreadUtils;

/**
 *
 * @author Terry Packer
 */
public class RealtimeDriverTest extends DeviceEventAdapter implements ExceptionListener{
    public static final int FRAME_TYPE_BACNET_DATA_NOT_EXPECTING_REPLY = 0x06;
    public static final int FRAME_TYPE_BACNET_DATA_EXPECTING_REPLY = 0x05;
    
    public static void main(String[] args) {
        if(args == null || args.length != 1) {
            System.out.println("Location of properties file required");
            return;
        }
        
        File properties = new File(args[0]);
        if(!properties.exists()) {
            System.out.println("Unable to find properties file.");
            return;
        }
        
        try {
            RealtimeDriverTest test = new RealtimeDriverTest(properties);
            //test.sendSimpleWhois();
            test.sendBACnet4JWhois();
        } catch (Exception e) {
            e.printStackTrace();
        } 
        
    }

    private final Properties driverProperties;
    private final RealtimeDriver driver;
    
    
    protected byte thisStation = 18;
    protected int retryCount = 1;
    protected int localNetworkNumber = 1;
    protected int deviceId = 1;
    protected String portName = "/dev/ttyUSB0";
    protected int baud = 34800;
    
    
    public RealtimeDriverTest(File properties) throws FileNotFoundException, IOException {
        driverProperties = new Properties();
        try(InputStream is = new FileInputStream(properties)){
            driverProperties.load(is);
        }
        //Setup the settings
        this.thisStation = Byte.parseByte(driverProperties.getProperty("MAC"));
        this.portName = driverProperties.getProperty("PORT");
        this.baud = Integer.parseInt(driverProperties.getProperty("BAUD"));
        this.driver = new RealtimeDriver(driverProperties);
    }
    
    public void sendSimpleWhois() throws Exception{
        int fd = -1;
        try {
            fd = this.driver.setupPort(this.portName, this.baud);
            this.driver.setMac(fd, thisStation);
            
            RealtimeDriverInputStream is = new RealtimeDriverInputStream(driver, fd);
            RealtimeDriverOutputStream os = new RealtimeDriverOutputStream(driver, fd);
            
            //WHOIS Message, MAC will be set by underlying driver
            byte[] buffer = new byte[13];
            buffer[0] = FRAME_TYPE_BACNET_DATA_NOT_EXPECTING_REPLY;
            buffer[1] = (byte)0xFF;
            buffer[2] = (byte)0xFF;
            buffer[3] = (0x08 >> 8)&0xFF; //length 1
            buffer[4] = 0x08 & 0xFF; //length 2
            buffer[5] = 0x01;
            buffer[6] = 0x20;
            buffer[7] = (byte)0xFF;
            buffer[8] = (byte)0xFF;
            buffer[9] = 0x00;
            buffer[10] = (byte)0xFF;
            buffer[11] = (byte)0x10;
            buffer[12] = (byte)0x08;
            System.out.println("Sending WhoIs");
            os.write(buffer);
            System.out.println("Waiting for replies");
            int count = 0;
            byte[] inBuffer = new byte[25];
            while(count< 60) {
                int read = is.read(inBuffer);
                if(read > 0) {
                    System.out.print("Recieving (" + read + "): ");
                    for(int i=0; i<read; i++) {
                        System.out.print(String.format("0x%02X", inBuffer[i]) + " ");
                    }
                    System.out.print("\n");
                }
                Thread.sleep(500);
                count++;
            }
        }finally {
            if(fd > 0)
                this.driver.close(fd);
        }
    }
    
    
    public void sendBACnet4JWhois() throws Exception {
        
        int fd = -1;
        try {
            fd = this.driver.setupPort(this.portName, this.baud);
            this.driver.setMac(fd, thisStation);
            //Create the streams
            try(
                    RealtimeDriverInputStream is = new RealtimeDriverInputStream(driver, fd);
                    RealtimeDriverOutputStream os = new RealtimeDriverOutputStream(driver, fd);
                ){
    
                RealtimeMasterNode masterNode = new RealtimeMasterNode(portName, is, os, driverProperties, thisStation, retryCount, this.baud);
                Network network = new MstpNetwork(masterNode, localNetworkNumber);
                Transport transport = new DefaultTransport(network);
                transport.setTimeout(Transport.DEFAULT_TIMEOUT);
                transport.setSegTimeout(Transport.DEFAULT_SEG_TIMEOUT);
                transport.setSegWindow(Transport.DEFAULT_SEG_WINDOW);
                transport.setRetries(Transport.DEFAULT_RETRIES);
                
                LocalDevice localDevice = new LocalDevice(deviceId, transport);
                localDevice.getDeviceObject().writePropertyInternal(PropertyIdentifier.objectName, new CharacterString("Test Device"));
                localDevice.getDeviceObject().writePropertyInternal(PropertyIdentifier.vendorName, new CharacterString("InfiniteAutomation"));
                localDevice.getDeviceObject().writePropertyInternal(PropertyIdentifier.modelName, new CharacterString("Mango Automation"));
        
                localDevice.initialize();
                localDevice.getEventHandler().addListener(this);
                
                WhoIsRequest whoIs = new WhoIsRequest();
                localDevice.getExceptionDispatcher().addListener(this);
                localDevice.sendGlobalBroadcast(whoIs);
                
                //Get the responses
                Thread.sleep(30000);
            }
        
        }finally {
            if(fd > 0)
                this.driver.close(fd);
        }
    }

    @Override
    public void iAmReceived(RemoteDevice d) {
        System.out.println("Recieved Iam from " +  d.getAddress());
    }
    
    /* (non-Javadoc)
     * @see com.serotonin.bacnet4j.event.ExceptionListener#unimplementedVendorService(com.serotonin.bacnet4j.type.primitive.UnsignedInteger, com.serotonin.bacnet4j.type.primitive.UnsignedInteger, com.serotonin.bacnet4j.util.sero.ByteQueue)
     */
    @Override
    public void unimplementedVendorService(UnsignedInteger vendorId, UnsignedInteger serviceNumber,
            ByteQueue queue) {
        String message = "Received unimplemented vendor service: vendor id=" + vendorId + ", service number=" + serviceNumber
                + ", bytes (with context id)=" + queue;
        System.out.print(message);
    }

    /* (non-Javadoc)
     * @see com.serotonin.bacnet4j.event.ExceptionListener#receivedException(java.lang.Exception)
     */
    @Override
    public void receivedException(Exception e) {
        System.out.println(e.getMessage());
    }

    /* (non-Javadoc)
     * @see com.serotonin.bacnet4j.event.ExceptionListener#receivedThrowable(java.lang.Throwable)
     */
    @Override
    public void receivedThrowable(Throwable t) {
        System.out.println(t.getMessage());
    }
    
    class RealtimeDriverInputStream extends InputStream {
        
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
    
    class RealtimeDriverOutputStream extends OutputStream {
        
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
