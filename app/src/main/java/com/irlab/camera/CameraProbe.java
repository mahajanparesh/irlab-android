package com.irlab.camera;

import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads the Camera2 characteristics of every camera the OS exposes and classifies how
 * useful each one is for infrared work. Only facts the hardware reports are used; anything
 * Android does not report (for example whether an IR-cut filter is fitted) is stated as
 * an assumption in the verdict text.
 */
final class CameraProbe {

    enum IrClass {
        /** Sensor reports a near-infrared colour filter arrangement: genuine NIR data. */
        NIR_SENSOR,
        /** External (USB/UVC) camera: genuine NIR if it is a NoIR / night-vision module. */
        EXTERNAL,
        /** Depth (ToF) output: uses active IR, but delivers depth maps, not IR photographs. */
        DEPTH_SENSOR,
        /** Monochrome sensor: filter stack unknown, test empirically. */
        MONO_SENSOR,
        /** Ordinary RGB Bayer sensor behind an IR-cut filter: residual NIR only. */
        RGB_IR_CUT
    }

    static final class Report {
        String id;
        String parentId;            // non-null for a physical sub-camera of a logical camera
        int facing = -1;
        String facingLabel = "?";
        String hwLevel = "?";
        int hwLevelValue = -1;
        boolean manualSensor, manualPost, raw, mono, logical, depth, external;
        int cfa = -1;
        String cfaLabel = "not reported";
        Size pixelArray;
        SizeF physicalSize;
        Range<Long> exposureRange;
        Range<Integer> isoRange;
        Range<Integer> evRange;
        float evStep = 1f / 3f;
        Integer maxAnalogIso;
        float[] focalLengths = new float[0];
        float[] apertures = new float[0];
        Size largestRaw;
        float minFocusDistance;
        boolean flash;
        int sensorOrientation;
        List<String> physicalIds = new ArrayList<>();
        IrClass irClass = IrClass.RGB_IR_CUT;
        String verdict = "";

        boolean genuineIr() {
            return irClass == IrClass.NIR_SENSOR || irClass == IrClass.EXTERNAL;
        }

        String shortLabel() {
            StringBuilder b = new StringBuilder(facingLabel.toUpperCase(Locale.US)).append(' ').append(id);
            if (focalLengths.length > 0) b.append(String.format(Locale.US, " · %.1fmm", focalLengths[0]));
            return b.toString();
        }

        String irClassLabel() {
            switch (irClass) {
                case NIR_SENSOR: return "Genuine NIR sensor";
                case EXTERNAL: return "External camera";
                case DEPTH_SENSOR: return "Depth / ToF (IR-illuminated)";
                case MONO_SENSOR: return "Monochrome (filter unknown)";
                default: return "RGB + IR-cut filter (residual NIR only)";
            }
        }
    }

    private CameraProbe() {}

    /** Probes every camera the OS lists, plus the physical sub-cameras of logical cameras. */
    static List<Report> probeAll(CameraManager mgr) {
        List<Report> out = new ArrayList<>();
        String[] ids;
        try {
            ids = mgr.getCameraIdList();
        } catch (CameraAccessException e) {
            return out;
        }
        for (String id : ids) {
            try {
                CameraCharacteristics c = mgr.getCameraCharacteristics(id);
                Report r = build(id, null, c);
                out.add(r);
                for (String phys : r.physicalIds) {
                    try {
                        out.add(build(phys, id, mgr.getCameraCharacteristics(phys)));
                    } catch (Exception ignored) {
                        // some OEMs list physical IDs that are not queryable by apps
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** Only the cameras that can be opened directly (no physical sub-cameras). */
    static List<Report> openable(List<Report> all) {
        List<Report> out = new ArrayList<>();
        for (Report r : all) if (r.parentId == null) out.add(r);
        return out;
    }

    static Report build(String id, String parentId, CameraCharacteristics c) {
        Report r = new Report();
        r.id = id;
        r.parentId = parentId;

        Integer facing = c.get(CameraCharacteristics.LENS_FACING);
        r.facing = facing == null ? -1 : facing;
        if (facing != null) {
            if (facing == CameraCharacteristics.LENS_FACING_BACK) r.facingLabel = "Back";
            else if (facing == CameraCharacteristics.LENS_FACING_FRONT) r.facingLabel = "Front";
            else r.facingLabel = "External";
        }

        Integer level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        r.hwLevelValue = level == null ? -1 : level;
        if (level != null) {
            switch (level) {
                case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY: r.hwLevel = "LEGACY"; break;
                case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED: r.hwLevel = "LIMITED"; break;
                case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL: r.hwLevel = "FULL"; break;
                case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3: r.hwLevel = "LEVEL_3"; break;
                case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL: r.hwLevel = "EXTERNAL"; break;
                default: r.hwLevel = String.valueOf(level);
            }
        }
        r.external = (level != null && level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL)
                || (facing != null && facing == CameraCharacteristics.LENS_FACING_EXTERNAL);

        int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (caps != null) {
            for (int cap : caps) {
                if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) r.manualSensor = true;
                if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING) r.manualPost = true;
                if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) r.raw = true;
                if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT) r.depth = true;
                if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) r.logical = true;
                if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME) r.mono = true;
            }
        }

        Integer cfa = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
        if (cfa != null) {
            r.cfa = cfa;
            switch (cfa) {
                case CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB: r.cfaLabel = "RGGB Bayer"; break;
                case CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG: r.cfaLabel = "GRBG Bayer"; break;
                case CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG: r.cfaLabel = "GBRG Bayer"; break;
                case CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR: r.cfaLabel = "BGGR Bayer"; break;
                case CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGB: r.cfaLabel = "RGB (non-Bayer)"; break;
                case CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO: r.cfaLabel = "MONO (no colour filter)"; break;
                case CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR: r.cfaLabel = "NIR (near-infrared filter)"; break;
                default: r.cfaLabel = "code " + cfa;
            }
        }

        r.pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        r.physicalSize = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        r.exposureRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        r.isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        r.maxAnalogIso = c.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY);
        r.evRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        android.util.Rational step = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        if (step != null) r.evStep = step.floatValue();
        float[] f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        if (f != null) r.focalLengths = f;
        float[] a = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
        if (a != null) r.apertures = a;
        Float mfd = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        r.minFocusDistance = mfd == null ? 0f : mfd;
        Boolean fl = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        r.flash = fl != null && fl;
        Integer so = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
        r.sensorOrientation = so == null ? 0 : so;

        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null && r.raw) {
            Size[] raws = map.getOutputSizes(ImageFormat.RAW_SENSOR);
            if (raws != null) {
                for (Size s : raws) {
                    if (r.largestRaw == null || (long) s.getWidth() * s.getHeight()
                            > (long) r.largestRaw.getWidth() * r.largestRaw.getHeight()) r.largestRaw = s;
                }
            }
        }
        if (r.logical) {
            Set<String> phys = c.getPhysicalCameraIds();
            r.physicalIds.addAll(phys);
        }

        classify(r);
        return r;
    }

    private static void classify(Report r) {
        if (r.cfa == CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR) {
            r.irClass = IrClass.NIR_SENSOR;
            r.verdict = "The sensor itself reports a near-infrared colour filter. Frames from this camera contain "
                    + "genuine NIR data.";
        } else if (r.external) {
            r.irClass = IrClass.EXTERNAL;
            r.verdict = "External USB (UVC) camera. If it is a NoIR / night-vision module (no IR-cut filter, or "
                    + "IR-cut switched out), its frames are genuine near-infrared images.";
        } else if (r.depth) {
            r.irClass = IrClass.DEPTH_SENSOR;
            r.verdict = "Depth-output camera (typically time-of-flight with an IR emitter). Android delivers depth "
                    + "and confidence maps from it, not infrared photographs.";
        } else if (r.mono || r.cfa == CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO) {
            r.irClass = IrClass.MONO_SENSOR;
            r.verdict = "Monochrome sensor. Android does not report whether an IR-cut filter is fitted, so run the "
                    + "IR remote test to measure NIR sensitivity.";
        } else {
            r.irClass = IrClass.RGB_IR_CUT;
            r.verdict = "Standard colour (" + r.cfaLabel + ") sensor. Phone camera modules like this have an IR-cut "
                    + "filter in the lens stack. Android doesn't expose that filter, but it is standard. Only a "
                    + "small amount of near-IR (roughly 700-750 nm) gets through. To capture genuine IR you need an "
                    + "IR-pass filter (720 nm) over the lens, a tripod and long exposures. IR MONO, false-colour and "
                    + "channel-swap on this camera are software renderings of visible light unless that filter is "
                    + "attached.";
        }
    }

    static String overallVerdict(List<Report> all, PackageManager pm) {
        boolean genuine = false, depth = false;
        for (Report r : all) {
            if (r.genuineIr()) genuine = true;
            if (r.irClass == IrClass.DEPTH_SENSOR) depth = true;
        }
        StringBuilder b = new StringBuilder();
        if (genuine) {
            b.append("This device exposes at least one camera that produces genuine near-infrared data.");
        } else {
            b.append("No dedicated infrared camera is exposed on ").append(Build.MANUFACTURER).append(' ')
                    .append(Build.MODEL).append(". Every camera reports a colour sensor, so they sit behind an "
                    + "IR-cut filter. What you can get: (1) residual-NIR photography with a 720 nm IR-pass filter, "
                    + "a tripod and long exposures (this is genuine IR, but faint), and (2) software visualisations "
                    + "(IR MONO, false colour) that make visible-light images look like IR. For real-time IR video "
                    + "or night vision, use an external NIR camera (see below).");
        }
        if (depth) b.append(" A depth/ToF camera is present but delivers depth, not IR images.");
        boolean ext = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_EXTERNAL);
        boolean host = pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST);
        b.append("\n\nUSB host: ").append(host ? "yes" : "no")
                .append(" · External cameras via Camera2: ").append(ext ? "supported (plug one in and it will appear here)"
                        : "not declared. A USB camera will need a dedicated UVC app instead of this one.");
        return b.toString();
    }

    static String toText(List<Report> all, PackageManager pm) {
        StringBuilder b = new StringBuilder();
        b.append("IR Lab hardware report - ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (Android ").append(Build.VERSION.RELEASE).append(")\n\n");
        b.append(overallVerdict(all, pm)).append("\n\n");
        for (Report r : all) {
            b.append("Camera ").append(r.id);
            if (r.parentId != null) b.append(" (physical, part of ").append(r.parentId).append(')');
            b.append('\n').append(details(r)).append("\nVerdict: ").append(r.verdict).append("\n\n");
        }
        return b.toString();
    }

    static String details(Report r) {
        StringBuilder b = new StringBuilder();
        b.append("Facing: ").append(r.facingLabel).append(" · HW level: ").append(r.hwLevel).append('\n');
        b.append("Colour filter: ").append(r.cfaLabel).append('\n');
        b.append("IR class: ").append(r.irClassLabel()).append('\n');
        if (r.pixelArray != null) {
            b.append(String.format(Locale.US, "Pixel array: %dx%d (%.1f MP)", r.pixelArray.getWidth(),
                    r.pixelArray.getHeight(), r.pixelArray.getWidth() * (double) r.pixelArray.getHeight() / 1e6));
            if (r.physicalSize != null) b.append(String.format(Locale.US, " · %.2fx%.2f mm",
                    r.physicalSize.getWidth(), r.physicalSize.getHeight()));
            b.append('\n');
        }
        if (r.focalLengths.length > 0) {
            b.append(String.format(Locale.US, "Focal length: %.2f mm", r.focalLengths[0]));
            if (r.apertures.length > 0) b.append(String.format(Locale.US, " · f/%.1f", r.apertures[0]));
            b.append('\n');
        }
        if (r.exposureRange != null) {
            b.append("Exposure: ").append(formatExposure(r.exposureRange.getLower())).append(" - ")
                    .append(formatExposure(r.exposureRange.getUpper())).append('\n');
        }
        if (r.isoRange != null) {
            b.append("ISO: ").append(r.isoRange.getLower()).append(" - ").append(r.isoRange.getUpper());
            if (r.maxAnalogIso != null) b.append(" (analog to ").append(r.maxAnalogIso).append(')');
            b.append('\n');
        }
        b.append("Manual sensor: ").append(yn(r.manualSensor)).append(" · Manual colour: ").append(yn(r.manualPost))
                .append(" · RAW: ").append(yn(r.raw));
        if (r.largestRaw != null) b.append(" (").append(r.largestRaw.getWidth()).append('x')
                .append(r.largestRaw.getHeight()).append(')');
        b.append('\n');
        b.append("Monochrome: ").append(yn(r.mono)).append(" · Depth output: ").append(yn(r.depth))
                .append(" · Logical multi-cam: ").append(yn(r.logical));
        if (!r.physicalIds.isEmpty()) b.append(' ').append(r.physicalIds);
        b.append("\nSensor orientation: ").append(r.sensorOrientation).append("° · Flash: ").append(yn(r.flash));
        return b.toString();
    }

    static String formatExposure(long ns) {
        double s = ns / 1e9;
        if (s >= 0.5) return String.format(Locale.US, "%.1fs", s);
        return "1/" + Math.round(1.0 / s) + "s";
    }

    private static String yn(boolean v) {
        return v ? "yes" : "no";
    }
}
