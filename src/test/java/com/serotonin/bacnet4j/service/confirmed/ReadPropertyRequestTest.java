package com.serotonin.bacnet4j.service.confirmed;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.serotonin.bacnet4j.LocalDevice;
import com.serotonin.bacnet4j.TestUtils;
import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.npdu.test.TestNetwork;
import com.serotonin.bacnet4j.npdu.test.TestNetworkMap;
import com.serotonin.bacnet4j.npdu.test.TestNetworkUtils;
import com.serotonin.bacnet4j.transport.DefaultTransport;
import com.serotonin.bacnet4j.type.constructed.Address;
import com.serotonin.bacnet4j.type.enumerated.ErrorClass;
import com.serotonin.bacnet4j.type.enumerated.ErrorCode;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;

public class ReadPropertyRequestTest {
    private final TestNetworkMap map = new TestNetworkMap();
    private final Address addr = TestNetworkUtils.toAddress(2);
    private LocalDevice localDevice;

    @Before
    public void before() throws Exception {
        localDevice = new LocalDevice(1, new DefaultTransport(new TestNetwork(map, 1, 0)));
        localDevice.initialize();
    }

    @After
    public void after() {
        localDevice.terminate();
    }

    @Test // 15.5.1.3.1
    public void errorTypes() {
        TestUtils.assertRequestHandleException(
                () -> new ReadPropertyRequest(new ObjectIdentifier(ObjectType.accessDoor, 0),
                        PropertyIdentifier.absenteeLimit).handle(localDevice, addr),
                ErrorClass.object, ErrorCode.unknownObject);

        TestUtils.assertRequestHandleException(
                () -> new ReadPropertyRequest(new ObjectIdentifier(ObjectType.device, 1),
                        PropertyIdentifier.absenteeLimit).handle(localDevice, addr),
                ErrorClass.property, ErrorCode.unknownProperty);

        TestUtils.assertRequestHandleException(
                () -> new ReadPropertyRequest(new ObjectIdentifier(ObjectType.device, 1),
                        PropertyIdentifier.systemStatus, new UnsignedInteger(1)).handle(localDevice, addr),
                ErrorClass.property, ErrorCode.propertyIsNotAnArray);

        TestUtils.assertRequestHandleException(
                () -> new ReadPropertyRequest(new ObjectIdentifier(ObjectType.device, 1), PropertyIdentifier.objectList,
                        new UnsignedInteger(10)).handle(localDevice, addr),
                ErrorClass.property, ErrorCode.invalidArrayIndex);
    }

    @Test // 15.5.2
    public void uninitializedDeviceId() throws BACnetException {
        // If this does not throw an error, then it's good.
        new ReadPropertyRequest(new ObjectIdentifier(ObjectType.device, 4194303), PropertyIdentifier.systemStatus)
                .handle(localDevice, addr);
    }

    @Test
    public void specialProperties() {
        TestUtils
                .assertRequestHandleException(
                        () -> new ReadPropertyRequest(new ObjectIdentifier(ObjectType.device, 4194303),
                                PropertyIdentifier.all).handle(localDevice, addr),
                        ErrorClass.services, ErrorCode.inconsistentParameters);
        TestUtils.assertRequestHandleException(
                () -> new ReadPropertyRequest(new ObjectIdentifier(ObjectType.device, 4194303),
                        PropertyIdentifier.required).handle(localDevice, addr),
                ErrorClass.services, ErrorCode.inconsistentParameters);
        TestUtils.assertRequestHandleException(
                () -> new ReadPropertyRequest(new ObjectIdentifier(ObjectType.device, 4194303),
                        PropertyIdentifier.optional).handle(localDevice, addr),
                ErrorClass.services, ErrorCode.inconsistentParameters);
    }
}
