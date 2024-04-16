package com.scanner.test2;

import com.nextbiometrics.biometrics.*;
import com.nextbiometrics.biometrics.event.*;
import com.nextbiometrics.devices.*;
import com.nextbiometrics.system.NBErrors;
import com.nextbiometrics.system.NBVersion;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class Application {
    private static final String SPI_DEFAULT_SPI_NAME = "/dev/spidev0.0";
    //Default SYSFS path to access GPIO pins 	
    private static final String SPI_DEFAULT_SYSFS_PATH = "/sys/class/gpio";
    private static final int SPI_DEFAULT_AWAKE_PIN = 69;
    private static final int SPI_DEFAULT_RESET_PIN = 12;
    private static final int SPI_DEFAULT_CHIP_SELECT_PIN = 18;
	
    private static int BlobSetCDK = NBDevice.BLOB_PARAMETER_SET_CDK;
    //CAK (customer assigned key) to be used for SDK Demo purposes
    private static String g_DefaultCAKIdentifier_string = "DefaultCAKKey1\0";	
    private static byte[] g_DefaultCAKIdentifier = g_DefaultCAKIdentifier_string.getBytes();
    private static byte g_DefaultCAK[] = {
        (byte)0x05, (byte)0x4B, (byte)0x38, (byte)0x3A, (byte)0xCF, (byte)0x5B, (byte)0xB8, (byte)0x01, (byte)0xDC, (byte)0xBB, (byte)0x85, (byte)0xB4, (byte)0x47, (byte)0xFF, (byte)0xF0, (byte)0x79, 
        (byte)0x77, (byte)0x90, (byte)0x90, (byte)0x81, (byte)0x51, (byte)0x42, (byte)0xC1, (byte)0xBF, (byte)0xF6, (byte)0xD1, (byte)0x66, (byte)0x65, (byte)0x0A, (byte)0x66, (byte)0x34, (byte)0x11
    };
	
    //CDK (customer defined key) to be used for SDK Demo purposes
    private static String NBU_CDK_IDENTIFIER_string = "Application Lock\0";
    private static byte[] NBU_CDK_IDENTIFIER = NBU_CDK_IDENTIFIER_string.getBytes();
    private static byte[] g_DefaultCDK = {
        (byte)0x6B, (byte)0xC5, (byte)0x51, (byte)0xD1, (byte)0x12, (byte)0xF7, (byte)0xE3, (byte)0x42, (byte)0xBD, (byte)0xDC, (byte)0xFB, (byte)0x5D, (byte)0x79, (byte)0x4E, (byte)0x5A, (byte)0xD6,
        (byte)0x54, (byte)0xD1, (byte)0xC9, (byte)0x90, (byte)0x28, (byte)0x05, (byte)0xCF, (byte)0x5E, (byte)0x4C, (byte)0x83, (byte)0x63, (byte)0xFB, (byte)0xC2, (byte)0x3C, (byte)0xF6, (byte)0xAB
    };

    //NB65100U Support
    private static String NBDEVICE_65100_AUTH1_ID_string = "AUTH1\0";
    private static byte[] NBDEVICE_65100_AUTH1_ID = NBDEVICE_65100_AUTH1_ID_string.getBytes();

    private static String NBDEVICE_65100_AUTH2_ID_string = "AUTH2\0";
    private static byte[] NBDEVICE_65100_AUTH2_ID = NBDEVICE_65100_AUTH2_ID_string.getBytes();

    private static byte[] defaultAuthKey1 = {
        (byte)0xDA, (byte)0x2E, (byte)0x35, (byte)0xB6, (byte)0xCB, (byte)0x96, (byte)0x2B, (byte)0x5F, (byte)0x9F, (byte)0x34, (byte)0x1F, (byte)0xD1, (byte)0x47, (byte)0x41, (byte)0xA0, (byte)0x4D,
        (byte)0xA4, (byte)0x09, (byte)0xCE, (byte)0xE8, (byte)0x35, (byte)0x48, (byte)0x3C, (byte)0x60, (byte)0xFB, (byte)0x13, (byte)0x91, (byte)0xE0, (byte)0x9E, (byte)0x95, (byte)0xB2, (byte)0x7F
    };

    private static int printUsage() {
        System.out.println("Usage:");
        System.out.println("nbbiometrics-create-enroll-template-console-sample-java <template type> <template filename> <command> (optional:<command options>)");
        System.out.println("\t<template type> - format of enroll template to be created (proprietary, iso, ansi, isocc)");
        System.out.println("\t<template filename> - filename to save created enroll template to");
        System.out.println("commands:");
        System.out.println("\t-usb - sample works in usb mode");
        System.out.println("\t-spi - sample works in spi mode and tries to connect to specified SPI");
        System.out.println("\tadditional spi options: <spi name> <awake pin> <reset pin> <chip select pin>");
        System.out.println(String.format("\t\t<spi name> - spi to connect to (default: %s)", SPI_DEFAULT_SPI_NAME));
        System.out.println(String.format("\t\t<awake pin> - awake pin number to be used (default: %d)", SPI_DEFAULT_AWAKE_PIN));
        System.out.println(String.format("\t\t<reset pin> - reset pin number to be used (default: %d)", SPI_DEFAULT_RESET_PIN));
        System.out.println(String.format("\t\t<chip select pin> - chip select pin number to be used (default: %d, specify \"-1\" if not available)", SPI_DEFAULT_CHIP_SELECT_PIN));
        return NBErrors.ERROR_FAILED;
    }
	
    private static void InitDevice(NBDevice device , boolean isDeviceCalibrationEnabled) {
        if(device !=null)
        {
    	    NBDeviceCapabilities deviceCapabilities = device.getCapabilities();
    	    Byte bSecurityModel = new Byte(deviceCapabilities.securityModel);
    	    if(bSecurityModel.intValue() != NBDeviceSecurityModel.ModelNone.getValue() && device.isSessionOpen())
    	        device.closeSession();
    		
            switch (bSecurityModel.intValue())
            {
                case 0: //No-match
                    break;
                case 1: //65100
                    device.openSession(NBDEVICE_65100_AUTH1_ID, defaultAuthKey1);
                    break;
                case 2: //65200-CAKOnly
                	device.openSession(g_DefaultCAKIdentifier, g_DefaultCAK);
                    break;
                case 3: //65200-CAKCDK
                    OpenCAKCDKSession(device);
                    break;
                default:
                    throw new IllegalArgumentException();
            }
    		
    	    if (deviceCapabilities.requiresExternalCalibrationData && isDeviceCalibrationEnabled)
            {
                SetCalibrationBlob(device);
            }
    	}
    }
    
    private static void OpenCAKCDKSession(NBDevice device){
        try
        {
            // Open secure session with the CDK
            device.openSession(NBU_CDK_IDENTIFIER, g_DefaultCDK);
            // This section shows the reset of CDK in the device
            // Delete CDK
            device.SetBlobParameter(BlobSetCDK, null);
            // Close CDK Session
            device.closeSession();
        }
        catch(Exception Ex) { }
        // Open secure session with the CAK
        device.openSession(g_DefaultCAKIdentifier, g_DefaultCAK);
        // Set CDK
        device.SetBlobParameter(BlobSetCDK, g_DefaultCDK);
        // Close CAK session
        device.closeSession();
        // Open secure session with the CDK
        device.openSession(NBU_CDK_IDENTIFIER, g_DefaultCDK);
    }
    
    private static void SetCalibrationBlob(NBDevice device) 
    {
        System.out.println("Setting Calibration Blob..");
    	Path blobPath = Paths.get("." , device.getSerialNumber() + "_calblob.bin" );
    	System.out.println("Calibration Blob File Name:" + blobPath.toString());
    	String blobFileName = blobPath.toString();
    	File blobFile = new File(blobFileName);
    	FileInputStream blobFileInputStream = null;
        try {
        	blobFileInputStream = new FileInputStream(blobFile);
        	byte _blobFile[] = new byte[(int)blobFile.length()];
        	blobFileInputStream.read(_blobFile);
            device.SetBlobParameter(NBDevice.BLOB_PARAMETER_CALIBRATION_DATA, _blobFile);
        }
        catch (Exception ex) {
        	System.out.println("Set Calibration Blob Failed." + ex);
        	return;
        }
        finally {
            try {
                if (blobFileInputStream != null) {
                	blobFileInputStream.close();
                }
            }
            catch (Exception ex) {
                System.out.println("Error while closing the stream. " + ex);
                return;
            }
        }
    }

    private static NBDeviceScanFormatInfo printDeviceInformation(NBDevice device) {
        System.out.println("Device id: " + device.getId());
        System.out.println("Device manufacturer: " + device.getManufacturer());
        System.out.println("Device model: " + device.getModel());
        System.out.println("Device serial number: " + device.getSerialNumber());
        System.out.println("Device product: " + device.getProduct());
        System.out.println("Device type: " + device.getType());
        System.out.println("Device connection type: " + device.getConnectionType());
        System.out.println("Device firmware version: " + device.getFirmwareVersion());
        System.out.println("Device supported scan formats:");
        NBDeviceScanFormatInfo[] supportedScans = device.getSupportedScanFormats();
        for (int i = 0; i < supportedScans.length; i++) {
            System.out.println(String.format("\t%d) %s, width: %d, height: %d, horizontal resolution: %d, vertical resolution: %d",
                    i + 1, supportedScans[i].getFormat(), supportedScans[i].getWidth(), supportedScans[i].getHeight(), supportedScans[i].getHorizontalResolution(), supportedScans[i].getVerticalResolution()));
        }
        System.out.println("Selecting first scan format ...");
        return supportedScans[0];
    }

    static class SampleOptions {
        public boolean useSpi;
        public String spiName;
        public String sysfsPath;
        public int awakePin;
        public int resetPin;
        public int chipSelectPin;
	public boolean bGenerateCalibrationData;
        public boolean isSpecImageType;
        public int imageType;
    }

    private static NBDevice connectToDeviceSpi(SampleOptions options) {
        if (options.chipSelectPin == -1) {
            System.out.println(String.format("Connecting to device %s (awake pin: %d, reset pin: %d) ...", options.spiName, options.awakePin, options.resetPin));
            return NBDevice.connectToSpi(options.spiName, options.sysfsPath, options.awakePin, options.resetPin, 0);
        }
        System.out.println(String.format("Connecting to device %s (awake pin: %d, reset pin: %d, chip select pin: %d) ...", options.spiName, options.awakePin, options.resetPin, options.chipSelectPin));
        return NBDevice.connectToSpi(options.spiName, options.sysfsPath, options.awakePin, options.resetPin, options.chipSelectPin, 0);
    }

    private static NBDevice connectToDeviceUsb(SampleOptions options) throws Exception {
        System.out.println("Enumerating devices ...");
        NBDevice[] devices = NBDevices.getDevices();
        if (devices.length == 0) {
            throw new Exception("No devices connected");
        }

        for (int i = 0; i < devices.length; i++) {
            System.out.println(String.format("%d)\t%s", i, devices[i].getId()));
            System.out.println(String.format("\t%s %s: %s", devices[i].getManufacturer(), devices[i].getModel(), devices[i].getSerialNumber()));
        }

        System.out.println("Connecting device 0 ...");
        return devices[0];
    }

    private static SampleOptions parseCommandOptions(String[] args) throws Exception {
        SampleOptions options = new SampleOptions();
        options.useSpi = false;
        options.spiName = SPI_DEFAULT_SPI_NAME;
        options.sysfsPath = SPI_DEFAULT_SYSFS_PATH;
        options.awakePin = SPI_DEFAULT_AWAKE_PIN;
        options.resetPin = SPI_DEFAULT_RESET_PIN;
        options.chipSelectPin = SPI_DEFAULT_CHIP_SELECT_PIN;
	options.bGenerateCalibrationData = false;
        options.isSpecImageType = false;
        options.imageType = 0;

        if (args.length > 0) {
            if (args[0].equals("-usb")) {
                options.useSpi = false;
            }
            if (args[0].equals("-spi")) {
                options.useSpi = true;
                if (args.length > 4) {
                    options.spiName = args[1].trim();
                    options.awakePin = Integer.parseInt(args[2].trim());
                    options.resetPin = Integer.parseInt(args[3].trim());
                    options.chipSelectPin = Integer.parseInt(args[4].trim());
                } else {
                    throw new Exception("Not enough parameters for SPI");
                }
            }
        }
        return options;
    }

    private static void saveTemplate(NBBiometricsContext context, String fileName, NBBiometricsTemplate template) throws IOException {
        byte[] data = context.saveTemplate(template);
        FileOutputStream fos = new FileOutputStream(fileName);
        try {
            fos.write(data);
        }
        finally {
            fos.close();
        }
    }

    private static NBBiometricsTemplateType parseTemplateType(String str) {
        if ("iso".equals(str))
            return NBBiometricsTemplateType.ISO;
        if ("isocc".equals(str))
            return NBBiometricsTemplateType.ISO_COMPACT_CARD;
        if ("ansi".equals(str))
            return NBBiometricsTemplateType.ANSI;
        return NBBiometricsTemplateType.PROPRIETARY;
    }

    public static void main(String[] args) {
        LibraryLoader.initLibraryPath();
        NBBiometricsContext context = null;
        NBDevice device = null;
        boolean terminate = false;
        try {
            if (args.length < 2) {
                printUsage();
                return;
            }
            NBBiometricsTemplateType templateType = parseTemplateType(args[0]);
            String templateFileName = args[1];
            NBBiometricsFingerPosition fingerPosition = NBBiometricsFingerPosition.UNKNOWN;
            SampleOptions options = parseCommandOptions(Arrays.copyOfRange(args, 2, args.length));
            System.out.println(String.format("Sample is in %s mode", options.useSpi ? "SPI" : "USB"));

            System.out.println("Getting biometrics library version ...");
            NBVersion version = NBBiometricsLibrary.getVersion();
            System.out.println("Biometrics library version: " + version);

            System.out.println("Getting devices library version ...");
            version = NBDevicesLibrary.getVersion();
            System.out.println("Devices library version: " + version);

            System.out.println("Initializing devices...");
            NBDevices.initialize(); terminate = true;
            device = options.useSpi ? connectToDeviceSpi(options) : connectToDeviceUsb(options);
            //Scan-Format and Device Calibration Support
            InitDevice(device, options.bGenerateCalibrationData);
            NBDeviceScanFormatInfo scanFormatInfo = printDeviceInformation(device);

            System.out.println("Creating biometrics context ...");
            context = new NBBiometricsContext(device);
            System.out.println("Get biometrics algorithm info ...");
            NBBiometricsAlgorithmInfo algorithmInfo = context.getAlgorithmInfo();
            System.out.println(String.format("Algorithm info: vendor id: %d, version: %s", algorithmInfo.getId(), algorithmInfo.getVersion().toString()));

            System.out.println("Create enroll template started, please put your fingerprint on sensor!");
            NBBiometricsCreateEnrollTemplateResult result = context.createEnrollTemplate(templateType, fingerPosition, scanFormatInfo, NBDevice.SCAN_TIMEOUT_INFINITE, new ScanPreview());
            if (result.getStatus() == NBBiometricsStatus.OK) {
                System.out.println(String.format("Enroll template created successfully. Saving template to %s ...", templateFileName));
                saveTemplate(context, templateFileName, result.getTemplate());
            } else {
                System.out.println("Failed to create enroll template, reason: " + result.getStatus());
            }
            System.out.println("Finished");
        }
        catch (Throwable ex) {
            ex.printStackTrace();
        }
        finally {
            if (device != null) device.dispose();
            if (context != null) context.dispose();
            if (terminate) {
                NBDevices.terminate();
            }
        }
    }
}

class ScanPreview implements NBBiometricsScanPreviewListener {
    @Override
    public void preview(NBBiometricsScanPreviewEvent event) {
        System.out.println(String.format("\tPreview: biometrics status: %s, scan status: %s, finger detect value: %d", event.getBiometricsStatus(), event.getScanStatus(), event.getFingerDetectValue()));
        if (event.getBiometricsStatus() == NBBiometricsStatus.NEED_MORE_SAMPLES) {
            System.out.println("\tPlease lift your fingerprint and press again (new sample requested)");
        }
    }
}
