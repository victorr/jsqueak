package JSqueak.input;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.ArrayList;
import java.util.List;

import JSqueak.SqueakVM;

/**
 * I'm JSqueak's keyboard driver.  I convert Java KeyEvents into Squeak
 * key and modifier key press events. 
 */
public class KeyboardQueue implements KeyListener
{
    /**
     * The size of the character queue.
     */
    private static final int TYPEAHEAD_LIMIT = 8;

    /**
     * See instance methods of InputSensor.
     */
    private static final int 
        SHIFT_KEY              =  8,
        CONTROL_KEY            = 16,
        COMMAND_KEY            = 64;
    
    /**
     * See ParagraphEditor class>>initializeCmdKeyShortcuts.
     */
    private static final char 
        CURSOR_HOME            =  1, 
        CURSOR_END             =  4, 
        CR_WITH_IDENT          = 13,
        SELECT_CURRENT_TYPE_IN = 27, // TODO: Interesting, find out what it does
        CURSOR_LEFT            = 28,
        CURSOR_RIGHT           = 29,
        CURSOR_UP              = 30,
        CURSOR_DOWN            = 31;

    /**
     * Squeak keys that do not map to Java key events. Order *MUST* match that of JAVA_KEYS.
     */
    private static final char[] SQUEAK_KEYS =
    {
        CURSOR_HOME,
        CURSOR_END,
        CR_WITH_IDENT,
        CURSOR_LEFT,
        CURSOR_RIGHT,
        CURSOR_UP,
        CURSOR_DOWN,
        SELECT_CURRENT_TYPE_IN,
    };

    /**
     * Counterpart of squeak keys.  Order *MUST* match that of SQUEAK_KEYS.
     */
    private static final int[] JAVA_KEYS =
    { 
        KeyEvent.VK_HOME,
        KeyEvent.VK_END,
        KeyEvent.VK_ENTER,
        KeyEvent.VK_LEFT,
        KeyEvent.VK_RIGHT,
        KeyEvent.VK_UP,
        KeyEvent.VK_DOWN,
        KeyEvent.VK_BACK_QUOTE,
    };
    
    private final SqueakVM fSqueakVM;
    
    private final List fCharQueue = new ArrayList();
    
    private int fModifierKeys = 0;
    
    public KeyboardQueue( SqueakVM squeakVM )
    {
        fSqueakVM = squeakVM;
    }
    
    // -- JSqueak interface
    
    public int peek() 
    {
        return fCharQueue.isEmpty() ? 0 : keycode( (Character) fCharQueue.get( 0 ) ); 
    }
    
    public int next() 
    {
        return keycode( (Character) fCharQueue.remove( 0 ) ); 
    }

    public int modifierKeys() 
    {
        return fModifierKeys;
    }
    
    // -- KeyListener methods
    
    public void keyPressed( KeyEvent event ) 
    {
        fModifierKeys = mapModifierKey( event );
        char keyChar = mapSpecialKey( event );
        if ( keyChar != KeyEvent.CHAR_UNDEFINED )
            addToQueue( keyChar );
    }
    
    public void keyReleased( KeyEvent event ) 
    {
        fModifierKeys = mapModifierKey( event );
    }
    
    public  void keyTyped( KeyEvent event )
    {
        // Ignore the return key, mapSpecialKey() took care of it
        if ( event.getKeyChar() == '\n' )
            return;
        
        addToQueue( event.getKeyChar() );
    }

    // -- Private methods
    
    private void addToQueue( char keyChar )
    {
        if ( fCharQueue.size() < TYPEAHEAD_LIMIT )
            fCharQueue.add( new Character( keyChar ) );
    }
    
    private static int mapModifierKey( KeyEvent event ) 
    {
        int modifiers = 0;
        if ( event.isShiftDown() )
            modifiers |= SHIFT_KEY;
        if ( event.isControlDown() )
            modifiers |= CONTROL_KEY;
        if ( event.isAltDown() || event.isMetaDown() )
            modifiers |= COMMAND_KEY;
        
        return modifiers;
    }
    
    private static char mapSpecialKey( KeyEvent evt ) 
    {
        int specialKeyIndex = 0;
        while ( specialKeyIndex < JAVA_KEYS.length && JAVA_KEYS[ specialKeyIndex ] != evt.getKeyCode() ) 
            specialKeyIndex++;
        if ( specialKeyIndex < JAVA_KEYS.length )
            return SQUEAK_KEYS[ specialKeyIndex ];
        
        if ( evt.isAltDown() )
            return Character.toLowerCase( (char) evt.getKeyCode() );
        
        return KeyEvent.CHAR_UNDEFINED;
    }

    private static int keycode( Character c ) 
    {
        return c.charValue() & 255; 
    }
}
