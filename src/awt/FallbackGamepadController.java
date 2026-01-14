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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fallback gamepad implementation using AWT key events
 * This provides basic gamepad support when JInput native libraries are unavailable
 * Maps keyboard keys to simulate gamepad controls
 */
public class FallbackGamepadController implements GamepadController {
    
    private static final Logger LOGGER = Logger.getLogger(FallbackGamepadController.class.getName());
    
    // Gamepad-style key bindings
    private static final int[] GAMEPAD_KEYS = {
        KeyEvent.VK_W, KeyEvent.VK_A, KeyEvent.VK_S, KeyEvent.VK_D, // WASD movement
        KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, // Arrow keys
        KeyEvent.VK_SPACE, KeyEvent.VK_ENTER, KeyEvent.VK_ESCAPE,
        KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_3, KeyEvent.VK_4, KeyEvent.VK_5, // Weapon keys
        KeyEvent.VK_COMMA, KeyEvent.VK_PERIOD, // Weapon switching
        KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT
    };
    
    private final EventObserver<EventHandler> eventObserver;
    private volatile boolean enabled = false;
    
    public FallbackGamepadController(EventObserver<EventHandler> eventObserver) {
        this.eventObserver = eventObserver;
        LOGGER.info("Fallback gamepad controller initialized (keyboard-based)");
    }
    
    /**
     * Start the fallback gamepad controller
     */
    public void start() {
        enabled = true;
        LOGGER.info("Fallback gamepad controller started - use keyboard controls");
        LOGGER.info("Controls: WASD/Arrows=Move, Space/Enter=Fire, 1-5=Weapons, Comma/Period=Switch");
    }
    
    /**
     * Stop the fallback gamepad controller
     */
    public void stop() {
        enabled = false;
        LOGGER.info("Fallback gamepad controller stopped");
    }
    
    /**
     * Check if fallback mode is active
     */
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Get gamepad-like status (always returns 1 since we simulate with keyboard)
     */
    public int getGamepadCount() {
        return enabled ? 1 : 0;
    }
    
    /**
     * Simulate gamepad button press via keyboard key
     */
    public void simulateButtonPress(int keyCode) {
        if (!enabled) return;
        
        Signals.ScanCode scanCode = mapKeyToGamepadAction(keyCode, true);
        if (scanCode != null) {
            sendDoomEvent(scanCode, true);
        }
    }
    
    /**
     * Simulate gamepad button release via keyboard key
     */
    public void simulateButtonRelease(int keyCode) {
        if (!enabled) return;
        
        Signals.ScanCode scanCode = mapKeyToGamepadAction(keyCode, false);
        if (scanCode != null) {
            sendDoomEvent(scanCode, false);
        }
    }
    
    /**
     * Map keyboard key to gamepad action
     */
    private Signals.ScanCode mapKeyToGamepadAction(int keyCode, boolean isPress) {
        switch (keyCode) {
            // Movement
            case KeyEvent.VK_W:
            case KeyEvent.VK_UP:
                return isPress ? Signals.ScanCode.SC_UP : null;
            case KeyEvent.VK_S:
            case KeyEvent.VK_DOWN:
                return isPress ? Signals.ScanCode.SC_DOWN : null;
            case KeyEvent.VK_A:
            case KeyEvent.VK_LEFT:
                return isPress ? Signals.ScanCode.SC_LEFT : null;
            case KeyEvent.VK_D:
            case KeyEvent.VK_RIGHT:
                return isPress ? Signals.ScanCode.SC_RIGHT : null;
                
            // Actions
            case KeyEvent.VK_SPACE:
            case KeyEvent.VK_ENTER:
                return Signals.ScanCode.SC_ENTER;
            case KeyEvent.VK_ESCAPE:
                return Signals.ScanCode.SC_ESCAPE;
            case KeyEvent.VK_CONTROL:
                return Signals.ScanCode.SC_LCTRL;
            case KeyEvent.VK_SHIFT:
                return Signals.ScanCode.SC_LSHIFT;
                
            // Weapons
            case KeyEvent.VK_1:
                return Signals.ScanCode.SC_1;
            case KeyEvent.VK_2:
                return Signals.ScanCode.SC_2;
            case KeyEvent.VK_3:
                return Signals.ScanCode.SC_3;
            case KeyEvent.VK_4:
                return Signals.ScanCode.SC_4;
            case KeyEvent.VK_5:
                return Signals.ScanCode.SC_5;
            case KeyEvent.VK_COMMA:
                return Signals.ScanCode.SC_COMMA;
            case KeyEvent.VK_PERIOD:
                return Signals.ScanCode.SC_PERIOD;
                
            default:
                return null;
        }
    }
    
    /**
     * Send Doom event
     */
    private void sendDoomEvent(Signals.ScanCode scanCode, boolean isPressed) {
        if (eventObserver != null && scanCode != null) {
            event_t event = isPressed ? scanCode.doomEventDown : scanCode.doomEventUp;
            eventObserver.feed(event);
            LOGGER.fine("Fallback controller sent event: " + scanCode + " " + (isPressed ? "DOWN" : "UP"));
        }
    }
    
    /**
     * Check if this is a gamepad key
     */
    public boolean isGamepadKey(int keyCode) {
        for (int key : GAMEPAD_KEYS) {
            if (key == keyCode) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Get control instructions
     */
    public String getControlInstructions() {
        return "FALLBACK CONTROLS (Gamepad Unavailable):\n" +
               "Movement: WASD or Arrow Keys\n" +
               "Fire: Space or Enter\n" +
               "Use/Open: E\n" +
               "Weapons: 1-5\n" +
               "Switch Weapons: Comma/Period\n" +
               "Strafe: Left Shift\n" +
               "Run: Left Ctrl\n" +
               "Menu: Escape";
    }
    
    @Override
    public boolean hasGamepads() {
        return false; // Fallback controller never has real gamepads
    }
    
    @Override
    public boolean isJInputAvailable() {
        return false; // Fallback controller means JInput is not available
    }
}