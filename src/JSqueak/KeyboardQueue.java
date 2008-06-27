package JSqueak;

import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

class KeyboardQueue extends KeyAdapter
{
    private final SqueakVM fSqueakVM;
    private final List fCharQueue= new ArrayList();
    
    KeyboardQueue( SqueakVM squeakVM )
    {
        fSqueakVM = squeakVM;
    }
    
    public void keyTyped( KeyEvent evt )
    {
        if (fCharQueue.size() < 8) 
        {
            // typeahead limit
            fCharQueue.add(new Character(evt.getKeyChar())); 
        }
        fSqueakVM.wakeVM();
    }
    
    private final int keycode(Character c) 
    {
        return c.charValue() & 255; 
    }
    
    int peek() 
    {
        return fCharQueue.isEmpty()? 0 : keycode((Character)fCharQueue.get(0)); 
    }
    
    int next() 
    {
        return keycode((Character)fCharQueue.remove(0)); 
    }
}