package com.amplogik.shuttlepro;

import java.util.UUID;

import com.bitwig.extension.api.PlatformType;
import com.bitwig.extension.controller.AutoDetectionMidiPortNamesList;
import com.bitwig.extension.controller.ControllerExtensionDefinition;
import com.bitwig.extension.controller.HardwareDeviceMatcherList;
import com.bitwig.extension.controller.UsbDeviceMatcher;
import com.bitwig.extension.controller.UsbEndpointMatcher;
import com.bitwig.extension.controller.UsbInterfaceMatcher;
import com.bitwig.extension.controller.api.ControllerHost;
import com.bitwig.extension.controller.api.UsbTransferDirection;
import com.bitwig.extension.controller.api.UsbTransferType;

public class ShuttleProExtensionDefinition extends ControllerExtensionDefinition {
    private static final UUID DRIVER_ID = UUID.fromString("3b4111ee-9702-4949-81b8-a38bdfe55040");

    @Override public String getName()             { return "Contour ShuttlePro v2"; }
    @Override public String getAuthor()           { return "amplogik"; }
    @Override public String getVersion()          { return "0.1.0"; }
    @Override public UUID   getId()               { return DRIVER_ID; }
    @Override public String getHardwareVendor()   { return "Contour Design"; }
    @Override public String getHardwareModel()    { return "ShuttlePro v2"; }
    @Override public int    getRequiredAPIVersion() { return 25; }
    @Override public int    getNumMidiInPorts()   { return 0; }
    @Override public int    getNumMidiOutPorts()  { return 0; }

    @Override
    public void listAutoDetectionMidiPortNames(AutoDetectionMidiPortNamesList list, PlatformType platformType) {
        // USB-only extension, no MIDI ports to declare.
    }

    @Override
    public void listHardwareDevices(HardwareDeviceMatcherList list) {
        UsbEndpointMatcher endpoint = new UsbEndpointMatcher(
                UsbTransferDirection.IN,
                UsbTransferType.INTERRUPT,
                "bEndpointAddress == 0x81");

        UsbInterfaceMatcher iface = new UsbInterfaceMatcher(
                "bInterfaceNumber == 0x00",
                endpoint);

        UsbDeviceMatcher device = new UsbDeviceMatcher(
                "Contour ShuttlePro v2",
                "idVendor == 0x0b33 && idProduct == 0x0030",
                iface);

        list.add(device);
    }

    @Override
    public ShuttleProExtension createInstance(ControllerHost host) {
        return new ShuttleProExtension(this, host);
    }
}
