/*
 * Copyright (C) 2026 euxaristia
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package awt;

import doom.event_t;
import doom.evtype_t;
import g.Signals;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Simplified JInput gamepad controller implementation
 * Properly detects and handles gamepad input including DualSense
 */
public class RealGamepadController implements GamepadController {
    
    private static final Logger LOGGER = Logger.getLogger(RealGamepadController.class.getName());
    
    private Object controllerEnvironment;
    private Object[] controllers;
    private final EventObserver<EventHandler> eventObserver;
    private volatile boolean running = false;
    private volatile boolean jinputAvailable = false;
    private Thread pollThread;
    
    // Controller state tracking
    private final Map<Object, ControllerState> controllerStates = new ConcurrentHashMap<>();

    // Gamepad button mappings
    private static final int BUTTON_A = 0;
    private static final int BUTTON_B = 1;
    private static final int BUTTON_X = 2;
    private static final int BUTTON_Y = 3;
    private static final int BUTTON_LB = 4;
    private static final int BUTTON_RB = 5;
    private static final int BUTTON_BACK = 6;
    private static final int BUTTON_START = 7;
    private static final int BUTTON_LS = 8;
    private static final int BUTTON_RS = 9;

    // POV state tracking for D-pad
    private float lastPovValue = 0.0f;

    // Repeat timer for analog stick menu navigation
    private int repeatCounter = 0;
    private static final int REPEAT_DELAY = 10; // Polls before repeat (~166ms at 60fps)

    // Track Cross button state to know when to send SC_1
    private boolean crossPressed = false;
    
    public RealGamepadController(EventObserver<EventHandler> eventObserver) {
        this.eventObserver = eventObserver;
        loadNativeLibrary();
        initializeControllers();
    }
    
    /**
     * Extract and load native JInput library from JAR
     */
    private void loadNativeLibrary() {
        try {
            String osName = System.getProperty("os.name").toLowerCase();
            String osArch = System.getProperty("os.arch").toLowerCase();
            String libName = null;
            String resourcePath = null;
            
            if (osName.contains("linux")) {
                if (osArch.contains("64")) {
                    libName = "libjinput-linux64.so";
                    resourcePath = "natives/" + libName;
                } else {
                    libName = "libjinput.so";
                    resourcePath = "natives/" + libName;
                }
            } else if (osName.contains("win")) {
                libName = "jinput-dx8_64.dll";
                resourcePath = libName;
            } else if (osName.contains("mac")) {
                libName = "libjinput-osx.jnilib";
                resourcePath = "natives/" + libName;
            }
            
            if (libName == null) {
                LOGGER.warning("Unsupported platform: " + osName + " " + osArch);
                return;
            }
            
            // Try to load from classpath (already in java.library.path)
            try {
                LOGGER.info("Attempting to load " + libName + " from system library path...");
                System.loadLibrary("jinput");
                LOGGER.info("✓ " + libName + " loaded successfully from system path");
                return;
            } catch (UnsatisfiedLinkError e) {
                LOGGER.info("Library not in system path, attempting to extract from JAR...");
            }
            
            // Extract from JAR to temp directory
            InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath);
            if (in == null) {
                LOGGER.warning("Native library not found in JAR: " + resourcePath);
                return;
            }
            
            File tempDir = new File(System.getProperty("java.io.tmpdir"), "mochadoom-natives");
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }
            
            File libFile = new File(tempDir, libName);
            
            // Extract library if not already extracted
            if (!libFile.exists()) {
                LOGGER.info("Extracting " + libName + " to " + libFile.getAbsolutePath());
                try (FileOutputStream out = new FileOutputStream(libFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                }
                in.close();
                
                // Mark file for deletion on JVM exit
                libFile.deleteOnExit();
            } else {
                in.close();
                LOGGER.info("Using existing " + libName + " at " + libFile.getAbsolutePath());
            }
            
            // Load the extracted library
            System.load(libFile.getAbsolutePath());
            LOGGER.info("✓ " + libName + " loaded successfully");
            
        } catch (UnsatisfiedLinkError e) {
            LOGGER.log(Level.SEVERE, "Failed to load JInput native library: " + e.getMessage(), e);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error loading native library", e);
        }
    }
    
    /**
     * Initialize controllers using reflection for Java 8 compatibility
     */
    private void initializeControllers() {
        try {
            LOGGER.info("Attempting to initialize JInput...");
            
            // Use reflection to avoid direct imports for Java 8 compatibility
            Class<?> controllerEnvClass = Class.forName("net.java.games.input.ControllerEnvironment");
            controllerEnvironment = controllerEnvClass.getMethod("getDefaultEnvironment").invoke(null);
            controllers = (Object[]) controllerEnvClass.getMethod("getControllers").invoke(controllerEnvironment);
            
            LOGGER.info("Found " + controllers.length + " input devices");
            
            int gamepadCount = 0;
            for (int i = 0; i < controllers.length; i++) {
                Object controller = controllers[i];
                if (isGamepad(controller)) {
                    gamepadCount++;
                    controllerStates.put(controller, new ControllerState());
                    LOGGER.info("✓ Gamepad detected: " + getControllerName(controller));
                }
            }
            
            if (gamepadCount > 0) {
                jinputAvailable = true;
                LOGGER.info("Gamepad support initialized with " + gamepadCount + " controller(s)");
                
                // Test if polling actually works
                try {
                    for (int j = 0; j < controllers.length; j++) {
                        Object controller = controllers[j];
                        if (isGamepad(controller)) {
                            controller.getClass().getMethod("poll").invoke(controller);
                            Object[] components = getControllerComponents(controller);
                            boolean hasActivity = false;
                            for (int k = 0; k < Math.min(5, components.length); k++) {
                                Object comp = components[k];
                                String id = getComponentIdentifier(comp);
                                float value = getComponentPollData(comp);
                                if (Math.abs(value) > 0.1f && !id.contains("pov")) {
                                    hasActivity = true;
                                    break;
                                }
                            }
                            if (hasActivity) {
                                LOGGER.info("✓ Controller polling works - activity detected");
                                startPolling();
                                return;
                            }
                        }
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Controller polling test failed", e);
                }
                
                // If we get here, polling didn't work properly
                LOGGER.warning("Controller detected but polling may not work - check jinput-linux64 library");
                startPolling();
            } else {
                LOGGER.warning("No gamepads found - keyboard controls only");
            }
            
        } catch (ClassNotFoundException e) {
            LOGGER.warning("JInput not available: " + e.getMessage());
        } catch (UnsatisfiedLinkError e) {
            LOGGER.severe("JInput native library missing: " + e.getMessage());
            LOGGER.severe("Please install JInput native libraries or add to java.library.path");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize JInput", e);
        }
    }
    
    /**
     * Check if a controller is a gamepad
     */
    private boolean isGamepad(Object controller) {
        try {
            Object typeObj = Class.forName("net.java.games.input.Controller").getMethod("getType").invoke(controller);
            String type = typeObj.toString();
            String name = getControllerName(controller);
            
            // Only accept actual gamepads, exclude motion sensors
            if (type.equals("Gamepad") && !name.contains("Motion Sensors")) {
                return true;
            }
            
            return false;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Get controller name
     */
    private String getControllerName(Object controller) {
        try {
            return (String) Class.forName("net.java.games.input.Controller").getMethod("getName").invoke(controller);
        } catch (Exception e) {
            return "Unknown";
        }
    }
    
    /**
     * Get controller type
     */
    private String getControllerType(Object controller) {
        try {
            Object type = Class.forName("net.java.games.input.Controller").getMethod("getType").invoke(controller);
            return type.toString();
        } catch (Exception e) {
            return "Unknown";
        }
    }
    
    /**
     * Get controller components
     */
    private Object[] getControllerComponents(Object controller) {
        try {
            return (Object[]) Class.forName("net.java.games.input.Controller").getMethod("getComponents").invoke(controller);
        } catch (Exception e) {
            return new Object[0];
        }
    }
    
    /**
     * Start polling thread
     */
    public synchronized void start() {
        if (running) return;
        
        running = true;
        if (jinputAvailable && pollThread == null) {
            startPolling();
        }
    }
    
    /**
     * Stop polling thread
     */
    public synchronized void stop() {
        running = false;
        if (pollThread != null) {
            pollThread.interrupt();
            try {
                pollThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            pollThread = null;
        }
    }
    
    /**
     * Start the polling thread
     */
    private void startPolling() {
        if (pollThread != null) return;
        
        pollThread = new Thread(() -> {
            try {
                LOGGER.info("Gamepad polling thread started");
                while (running && !Thread.currentThread().isInterrupted()) {
                    pollControllers();
                    Thread.sleep(16); // ~60 FPS
                }
            } catch (InterruptedException e) {
                LOGGER.info("Gamepad polling thread interrupted");
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in gamepad polling thread", e);
            } finally {
                LOGGER.info("Gamepad polling thread stopped");
            }
        }, "GamepadPoller");
        
        pollThread.setDaemon(true);
        pollThread.start();
    }
    
    /**
     * Poll all connected controllers for input
     */
    private void pollControllers() {
        if (!jinputAvailable) return;
        
        try {
            for (int i = 0; i < controllers.length; i++) {
                Object controller = controllers[i];
                if (isGamepad(controller)) {
                    pollController(controller);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error polling controllers", e);
        }
    }
    
    /**
     * Poll a single controller
     */
    private void pollController(Object controller) {
        try {
            // Poll the controller
            Class.forName("net.java.games.input.Controller").getMethod("poll").invoke(controller);
            
            // Get components
            Object[] components = getControllerComponents(controller);
            ControllerState state = controllerStates.get(controller);
            
            if (state == null) return;
            
            // Process each component
            for (int i = 0; i < components.length; i++) {
                Object component = components[i];
                String identifier = getComponentIdentifier(component);
                float value = getComponentPollData(component);
                boolean isAnalog = isComponentAnalog(component);

                // Special handling for POV (D-pad)
                if (identifier.equals("pov")) {
                    handlePovInput(value);
                    continue;
                }

                if (isAnalog) {
                    // Handle analog input (joysticks, triggers)
                    handleAnalogInput(identifier, value, state);
                } else {
                    // Handle digital input (buttons)
                    boolean pressed = value > 0.5f;
                    handleDigitalInput(identifier, pressed, state);
                }
            }
            
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Error polling controller", e);
        }
    }
    
    /**
     * Handle analog input
     */
    private void handleAnalogInput(String identifier, float value, ControllerState state) {
        int axisIndex = getAxisIndex(identifier);
        if (axisIndex >= 0) {
            float oldValue = state.axisValues[axisIndex];
            state.axisValues[axisIndex] = value;

            // Generate key events based on analog input
            generateAnalogKeyEvents(axisIndex, oldValue, value);
        }
    }
    
    /**
     * Handle digital input
     */
    private void handleDigitalInput(String identifier, boolean pressed, ControllerState state) {
        int buttonIndex = getButtonIndex(identifier);
        if (buttonIndex >= 0) {
            boolean oldPressed = state.buttonStates[buttonIndex];
            state.buttonStates[buttonIndex] = pressed;
            
            // Log button presses for debugging
            if (pressed && !oldPressed) {
                LOGGER.fine("Button pressed: " + identifier + " (index " + buttonIndex + ")");
            }
            
            // Generate key events
            if (pressed && !oldPressed) {
                generateButtonPressEvent(buttonIndex);
            } else if (!pressed && oldPressed) {
                generateButtonReleaseEvent(buttonIndex);
            }
        }
    }
    
    /**
     * Get component identifier using reflection
     */
    private String getComponentIdentifier(Object component) {
        try {
            return (String) Class.forName("net.java.games.input.Component$Identifier").getMethod("getName").invoke(
                Class.forName("net.java.games.input.Component").getMethod("getIdentifier").invoke(component));
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * Get component poll data
     */
    private float getComponentPollData(Object component) {
        try {
            return (Float) Class.forName("net.java.games.input.Component").getMethod("getPollData").invoke(component);
        } catch (Exception e) {
            return 0.0f;
        }
    }
    
    /**
     * Check if component is analog
     */
    private boolean isComponentAnalog(Object component) {
        try {
            return (Boolean) Class.forName("net.java.games.input.Component").getMethod("isAnalog").invoke(component);
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Get button index from identifier
     */
    private int getButtonIndex(String identifier) {
        // Handle DualSense button names
        if (identifier.equals("A")) return BUTTON_A; // Cross/A button
        if (identifier.equals("B")) return BUTTON_B; // Circle/B button
        if (identifier.equals("X")) return BUTTON_X; // Square/X button
        if (identifier.equals("Y")) return BUTTON_Y; // Triangle/Y button
        if (identifier.equals("Select")) return BUTTON_BACK;
        if (identifier.equals("Start")) return BUTTON_START;
        if (identifier.equals("Mode")) return BUTTON_BACK; // PS button
        
        // Handle thumb stick clicks
        if (identifier.equals("Left Thumb")) return BUTTON_LS;
        if (identifier.equals("Right Thumb")) return BUTTON_RS;
        
        // Handle traditional JInput button names
        if (identifier.equals("Button._0")) return BUTTON_A;
        if (identifier.equals("Button._1")) return BUTTON_B;
        if (identifier.equals("Button._2")) return BUTTON_X;
        if (identifier.equals("Button._3")) return BUTTON_Y;
        if (identifier.equals("Button._4")) return BUTTON_LB;
        if (identifier.equals("Button._5")) return BUTTON_RB;
        if (identifier.equals("Button._6")) return BUTTON_BACK;
        if (identifier.equals("Button._7")) return BUTTON_START;
        if (identifier.equals("Button._8")) return BUTTON_LS;
        if (identifier.equals("Button._9")) return BUTTON_RS;
        
        return -1;
    }
    
    /**
     * Get axis index from identifier
     */
    private int getAxisIndex(String identifier) {
        // Handle lowercase identifiers (DualSense uses these)
        if (identifier.equals("x")) return 0;  // Left stick X
        if (identifier.equals("y")) return 1;  // Left stick Y
        if (identifier.equals("rx")) return 2; // Right stick X
        if (identifier.equals("ry")) return 3; // Right stick Y
        if (identifier.equals("z")) return 4;  // Left trigger
        if (identifier.equals("rz")) return 5; // Right trigger

        // Also support traditional format
        if (identifier.equals("Axis.X")) return 0;
        if (identifier.equals("Axis.Y")) return 1;
        if (identifier.equals("Axis.RX")) return 2;
        if (identifier.equals("Axis.RY")) return 3;
        if (identifier.equals("Axis.Z")) return 4;
        if (identifier.equals("Axis.RZ")) return 5;

        return -1;
    }
    
    /**
     * Map button to ScanCode
     */
    private Signals.ScanCode getButtonScanCode(int button) {
        switch (button) {
            case BUTTON_A: return Signals.ScanCode.SC_ENTER; // Cross/Confirm
            case BUTTON_B: return Signals.ScanCode.SC_ESCAPE; // Back/Cancel
            case BUTTON_X: return Signals.ScanCode.SC_SPACE; // Use/Open
            case BUTTON_Y: return Signals.ScanCode.SC_1; // Weapon 1 / Quit confirm
            case BUTTON_LB: return Signals.ScanCode.SC_COMMA; // Previous weapon
            case BUTTON_RB: return Signals.ScanCode.SC_PERIOD; // Next weapon
            case BUTTON_BACK: return Signals.ScanCode.SC_ESCAPE; // Menu
            case BUTTON_START: return Signals.ScanCode.SC_PAUSE; // Pause
            case BUTTON_LS: return Signals.ScanCode.SC_LSHIFT; // Strafe modifier
            case BUTTON_RS: return Signals.ScanCode.SC_LSHIFT; // Strafe modifier
            default: return null; // No mapping
        }
    }
    
    /**
     * Generate analog key events (movement)
     */
    private void generateAnalogKeyEvents(int axisIndex, float oldValue, float value) {
        float threshold = 0.3f;

        if (axisIndex == 0) { // Left stick X - Left/Right movement
            boolean leftPressed = value < -threshold;
            boolean rightPressed = value > threshold;
            boolean wasLeftPressed = oldValue < -threshold;
            boolean wasRightPressed = oldValue > threshold;

            // Handle left direction
            if (leftPressed && !wasLeftPressed) {
                generateKeyEvent(Signals.ScanCode.SC_LEFT, true);
                repeatCounter = 0;
            } else if (!leftPressed && wasLeftPressed) {
                generateKeyEvent(Signals.ScanCode.SC_LEFT, false);
            } else if (leftPressed && wasLeftPressed) {
                // Send repeat events periodically for menu scrolling
                repeatCounter++;
                if (repeatCounter >= REPEAT_DELAY) {
                    generateKeyEvent(Signals.ScanCode.SC_LEFT, true);
                    repeatCounter = REPEAT_DELAY - 2;
                }
            }

            // Handle right direction
            if (rightPressed && !wasRightPressed) {
                generateKeyEvent(Signals.ScanCode.SC_RIGHT, true);
                repeatCounter = 0;
            } else if (!rightPressed && wasRightPressed) {
                generateKeyEvent(Signals.ScanCode.SC_RIGHT, false);
            } else if (rightPressed && wasRightPressed) {
                // Send repeat events periodically for menu scrolling
                repeatCounter++;
                if (repeatCounter >= REPEAT_DELAY) {
                    generateKeyEvent(Signals.ScanCode.SC_RIGHT, true);
                    repeatCounter = REPEAT_DELAY - 2;
                }
            }
        } else if (axisIndex == 1) { // Left stick Y - Up/Down movement
            boolean upPressed = value < -threshold;
            boolean downPressed = value > threshold;
            boolean wasUpPressed = oldValue < -threshold;
            boolean wasDownPressed = oldValue > threshold;

            // Handle up direction
            if (upPressed && !wasUpPressed) {
                generateKeyEvent(Signals.ScanCode.SC_UP, true);
                repeatCounter = 0;
            } else if (!upPressed && wasUpPressed) {
                generateKeyEvent(Signals.ScanCode.SC_UP, false);
            } else if (upPressed && wasUpPressed) {
                // Send repeat events periodically for menu scrolling
                repeatCounter++;
                if (repeatCounter >= REPEAT_DELAY) {
                    generateKeyEvent(Signals.ScanCode.SC_UP, true);
                    repeatCounter = REPEAT_DELAY - 2;
                }
            }

            // Handle down direction
            if (downPressed && !wasDownPressed) {
                generateKeyEvent(Signals.ScanCode.SC_DOWN, true);
                repeatCounter = 0;
            } else if (!downPressed && wasDownPressed) {
                generateKeyEvent(Signals.ScanCode.SC_DOWN, false);
            } else if (downPressed && wasDownPressed) {
                // Send repeat events periodically for menu scrolling
                repeatCounter++;
                if (repeatCounter >= REPEAT_DELAY) {
                    generateKeyEvent(Signals.ScanCode.SC_DOWN, true);
                    repeatCounter = REPEAT_DELAY - 2;
                }
            }
        } else if (axisIndex == 4 || axisIndex == 5) { // Triggers - Fire
            boolean triggerPressed = value > threshold;
            boolean wasPressed = oldValue > threshold;

            if (triggerPressed && !wasPressed) {
                generateKeyEvent(Signals.ScanCode.SC_SPACE, true); // Fire
            } else if (!triggerPressed && wasPressed) {
                generateKeyEvent(Signals.ScanCode.SC_SPACE, false); // Release fire
            }
        }
    }
    
    /**
     * Generate button press event
     */
    private void generateButtonPressEvent(int button) {
        if (button == BUTTON_A) {
            crossPressed = true;
            // Cross button: Send both ENTER (for menus) and SC_1 (for quit screen)
            // Send ENTER first for normal menu navigation
            generateKeyEvent(Signals.ScanCode.SC_ENTER, true);
            // Also send SC_1 for quit confirmation screen compatibility
            generateKeyEvent(Signals.ScanCode.SC_1, true);
        } else {
            Signals.ScanCode scanCode = getButtonScanCode(button);
            if (scanCode != null) {
                generateKeyEvent(scanCode, true);
            }
        }
    }

    /**
     * Generate button release event
     */
    private void generateButtonReleaseEvent(int button) {
        if (button == BUTTON_A) {
            crossPressed = false;
            // Cross button: Release both ENTER and SC_1
            generateKeyEvent(Signals.ScanCode.SC_ENTER, false);
            generateKeyEvent(Signals.ScanCode.SC_1, false);
        } else {
            Signals.ScanCode scanCode = getButtonScanCode(button);
            if (scanCode != null) {
                generateKeyEvent(scanCode, false);
            }
        }
    }
    
    /**
     * Generate key event
     */
    private void generateKeyEvent(Signals.ScanCode scanCode, boolean pressed) {
        if (eventObserver != null && scanCode != null) {
            event_t event = pressed ? scanCode.doomEventDown : scanCode.doomEventUp;
            eventObserver.feed(event);
            LOGGER.fine("Sent event: " + scanCode + " " + (pressed ? "DOWN" : "UP"));
        }
    }
    
    /**
     * Generate key event from KeyEvent (for analog input)
     */
    private void generateKeyEvent(int keyCode, boolean pressed) {
        if (eventObserver != null) {
            try {
                byte[] map = (byte[]) Class.forName("g.Signals$ScanCode").getDeclaredField("map").get(null);
                Signals.ScanCode[] v = (Signals.ScanCode[]) Class.forName("g.Signals$ScanCode").getDeclaredField("v").get(null);
                
                byte scanCodeIndex = map[keyCode & 0xFF];
                if (scanCodeIndex != 0 && scanCodeIndex < v.length) {
                    Signals.ScanCode scanCode = v[scanCodeIndex & 0xFF];
                    generateKeyEvent(scanCode, pressed);
                }
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "Failed to generate key event for key code: " + keyCode, e);
            }
        }
    }
    
    /**
     * Print gamepad controls
     */
    public void printGamepadControls() {
        LOGGER.info("========================");
        LOGGER.info("GAMEPAD CONTROLS");
        LOGGER.info("========================");
        LOGGER.info("A Button = Fire/Confirm");
        LOGGER.info("B Button = Escape/Back");
        LOGGER.info("X Button = Use/Open");
        LOGGER.info("Y Button = Weapon Slot 1");
        LOGGER.info("LB Button = Previous Weapon");
        LOGGER.info("RB Button = Next Weapon");
        LOGGER.info("Back = Menu");
        LOGGER.info("Start Button = Pause");
        LOGGER.info("Left/Right Triggers = Fire");
        LOGGER.info("Left Stick = Movement");
        LOGGER.info("Right Stick Y = Weapon Switch");
        LOGGER.info("========================");
    }
    
    /**
     * Print keyboard controls
     */
    public void printKeyboardControls() {
        LOGGER.info("========================");
        LOGGER.info("KEYBOARD CONTROLS");
        LOGGER.info("========================");
        LOGGER.info("WASD/Arrows = Movement");
        LOGGER.info("Space/Enter = Fire");
        LOGGER.info("E = Use/Open");
        LOGGER.info("1-5 = Weapon Slots");
        LOGGER.info(",/. = Switch Weapons");
        LOGGER.info("Escape = Menu");
        LOGGER.info("========================");
    }
    
    /**
     * Check if JInput is available
     */
    public boolean isJInputAvailable() {
        return jinputAvailable;
    }
    
    /**
     * Check if any gamepads are connected
     */
    public boolean hasGamepads() {
        return jinputAvailable && !controllerStates.isEmpty();
    }
    
    /**
     * Get number of connected gamepads
     */
    public int getGamepadCount() {
        return controllerStates.size();
    }

    /**
     * Handle POV (D-pad) input
     * POV values: 0.0=centered, 0.25=up, 0.5=right, 0.75=down, 1.0=left
     */
    private void handlePovInput(float povValue) {
        // Define POV direction constants
        final float POV_UP = 0.25f;
        final float POV_RIGHT = 0.5f;
        final float POV_DOWN = 0.75f;
        final float POV_LEFT = 1.0f;

        // Check which direction is pressed based on POV value
        boolean upPressed = povValue == POV_UP;
        boolean downPressed = povValue == POV_DOWN;
        boolean leftPressed = povValue == POV_LEFT;
        boolean rightPressed = povValue == POV_RIGHT;

        // Get previous direction from last POV value
        boolean wasUp = lastPovValue == POV_UP;
        boolean wasDown = lastPovValue == POV_DOWN;
        boolean wasLeft = lastPovValue == POV_LEFT;
        boolean wasRight = lastPovValue == POV_RIGHT;

        // Release previous direction keys
        if (wasUp && !upPressed) generateKeyEvent(Signals.ScanCode.SC_UP, false);
        if (wasDown && !downPressed) generateKeyEvent(Signals.ScanCode.SC_DOWN, false);
        if (wasLeft && !leftPressed) generateKeyEvent(Signals.ScanCode.SC_LEFT, false);
        if (wasRight && !rightPressed) generateKeyEvent(Signals.ScanCode.SC_RIGHT, false);

        // Press new direction keys
        if (upPressed && !wasUp) generateKeyEvent(Signals.ScanCode.SC_UP, true);
        if (downPressed && !wasDown) generateKeyEvent(Signals.ScanCode.SC_DOWN, true);
        if (leftPressed && !wasLeft) generateKeyEvent(Signals.ScanCode.SC_LEFT, true);
        if (rightPressed && !wasRight) generateKeyEvent(Signals.ScanCode.SC_RIGHT, true);

        // Update last POV value
        lastPovValue = povValue;
    }

    /**
     * Controller state tracking
     */
    private static class ControllerState {
        final boolean[] buttonStates = new boolean[20];
        final float[] axisValues = new float[10];
        
        ControllerState() {
            // Initialize with default values
            for (int i = 0; i < axisValues.length; i++) {
                axisValues[i] = 0.0f;
            }
        }
    }
}