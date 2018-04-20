/**
 * Copyright (C) 2018 Infinite Automation Software. All rights reserved.
 */
package com.serotonin.bacnet4j.npdu.mstp;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Properties;

import com.serotonin.bacnet4j.LocalDevice;
import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.event.DeviceEventAdapter;
import com.serotonin.bacnet4j.event.ExceptionListener;
import com.serotonin.bacnet4j.npdu.Network;
import com.serotonin.bacnet4j.service.unconfirmed.WhoIsRequest;
import com.serotonin.bacnet4j.transport.DefaultTransport;
import com.serotonin.bacnet4j.transport.Transport;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.CharacterString;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;
import com.serotonin.bacnet4j.util.sero.ByteQueue;
import com.serotonin.bacnet4j.util.sero.JsscSerialPortOutputStream;
import com.serotonin.bacnet4j.util.sero.ThreadUtils;

import jssc.SerialPort;
import jssc.SerialPortException;

/**
 *
 * @author Terry Packer
 */
public class RealtimeDriverTest extends DeviceEventAdapter implements ExceptionListener{

    public void testSendWhois() throws SerialPortException, NoSuchFieldException, SecurityException, IOException, IllegalArgumentException, IllegalAccessException {

        //Load the driver properties
        Properties driverProperties = new Properties();
        try(InputStream is = RealtimeDriverTest.class.getResourceAsStream("realtime-driver.properties")){
            driverProperties.load(is);
        }
        RealtimeDriver driver = new RealtimeDriver(driverProperties);
        
        
        byte thisStation = 0x55;
        int retryCount = 1;
        int localNetworkNumber = 1;
        int deviceId = 1;
        String portName = "/dev/tty-USB0";
        int baud = 34800;
        
        SerialPort port = new SerialPort(portName);
        port.openPort();
        
        port.setFlowControlMode(0);
        port.setParams(baud, 8, 1, 0);
        Field handleRef = port.getClass().getDeclaredField("portHandle");
        handleRef.setAccessible(true);
        Long handleId = (Long)handleRef.get(port);
        int handle = handleId.intValue();
        
 
        
        //Create the streams
        try(
                //TODO Reading from this stream will cause seg fault.
                //JsscSerialPortInputStream is = new JsscSerialPortInputStream(port);
                RealtimeDriverInputStream is = new RealtimeDriverInputStream(driver, handle);
                JsscSerialPortOutputStream os = new JsscSerialPortOutputStream(port);
            ){

        RealtimeMasterNode masterNode = new RealtimeMasterNode(portName, handle, is, os, driverProperties, thisStation, retryCount);
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

        
        WhoIsRequest whoIs = new WhoIsRequest();
        localDevice.getExceptionDispatcher().addListener(this);
        localDevice.sendGlobalBroadcast(whoIs);
        
        //Get the responses
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
            System.arraycopy(buf, 0, b, off, length);
            return length;
        }
        
        @Override
        public int available() throws IOException {
            return driver.available(handle);
        }
    }
    
}
