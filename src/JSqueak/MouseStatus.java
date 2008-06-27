package JSqueak;

import java.awt.event.MouseEvent;

import javax.swing.event.MouseInputAdapter;

class MouseStatus extends MouseInputAdapter 
{
    private final SqueakVM fSqueakVM;
    
    int fX, fY;
    int fButtons;
    
    private final static int RED = 4;
    private final static int YELLOW = 2;
    private final static int BLUE = 1;
    
    MouseStatus( SqueakVM squeakVM )
    {
        fSqueakVM = squeakVM;
    }
    
    private int mapButton(MouseEvent evt) 
    {
        switch (evt.getButton()) 
        {
            case MouseEvent.BUTTON1:
                if (evt.isControlDown()) 
                    return YELLOW;
                if (evt.isAltDown()) 
                    return BLUE;
                return RED;
            case MouseEvent.BUTTON2:    return BLUE;        // middle (frame menu)
            case MouseEvent.BUTTON3:    return YELLOW;  // right (pane menu)
            case MouseEvent.NOBUTTON:   return 0;
        }
        throw new RuntimeException("unknown mouse button in event"); 
    }
    
    public void mouseMoved(MouseEvent evt) 
    {
        fX = evt.getX();
        fY = evt.getY();
        fSqueakVM.wakeVM(); 
    }
    
    public void mouseDragged(MouseEvent evt) 
    {
        fX= evt.getX();
        fY= evt.getY();
        fSqueakVM.wakeVM(); 
    }
    
    public void mousePressed(MouseEvent evt) 
    {
        fButtons |= mapButton(evt);
        fSqueakVM.wakeVM(); 
    }
    
    public void mouseReleased(MouseEvent evt) 
    {
        fButtons &= ~mapButton(evt);
        fSqueakVM.wakeVM();
    }
}