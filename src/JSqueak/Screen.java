/*
Screen.java
Copyright (c) 2008  Daniel H. H. Ingalls, Sun Microsystems, Inc.  All rights reserved.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
*/

/*
 The author is indebted to Helge Horch for the outer framework of this VM.
 I knew nothing about how to write a Java program, and he provided the basic
 structure of main program, mouse input and display output for me.
 The object model, interpreter and primitives are all my design, but Helge
 continued to help whenever I was particularly stuck during the project.
*/

package JSqueak;

import JSqueak.input.InputNotifyThread;
import JSqueak.input.KeyboardQueue;
import JSqueak.input.MouseStatus;
import JSqueak.utils.ScreenUtils;
import JSqueak.utils.SqueakLogger;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.image.*;
import java.lang.reflect.InvocationTargetException;

public class Screen {
    Dimension fExtent;
    private int fDepth;
    private JFrame fFrame;
    private JPanel contentView;
    private JLabel fDisplay;
    private byte fDisplayBits[];
    private MouseStatus fMouseStatus;
    private KeyboardQueue fKeyboardQueue;
    private InputNotifyThread inputNotifyThread;

    private Timer fHeartBeat;
    private boolean fScreenChanged;
    private Object fVMSemaphore;

    private final static boolean WITH_HEARTBEAT = true;
    private final static int FPS = 30;

    // cf. http://doc.novsu.ac.ru/oreilly/java/awt/ch12_02.htm
    private final static byte kComponents[] =
            new byte[]{(byte) 255, 0, (byte) 240, (byte) 230,
                    (byte) 220, (byte) 210, (byte) 200, (byte) 190, (byte) 180, (byte) 170,
                    (byte) 160, (byte) 150, 110, 70, 30, 10};

    private final static ColorModel kBlackAndWhiteModel =
            new IndexColorModel(1, 2, kComponents, kComponents, kComponents);

    public Screen(String title, int width, int height, int depth, Object vmSema) {
        fVMSemaphore = vmSema;
        fExtent = new Dimension(width, height);
        fDepth = depth;
        fFrame = new JFrame(title);
        fFrame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        contentView = new JPanel(new BorderLayout());
        Icon noDisplay = new Icon() {
            public int getIconWidth() {
                return fExtent.width;
            }

            public int getIconHeight() {
                return fExtent.height;
            }

            public void paintIcon(Component c, Graphics g, int x, int y) {
            }
        };
        fDisplay = new JLabel(noDisplay);
        fDisplay.setSize(fExtent);
        fDisplay.setHorizontalAlignment(SwingConstants.LEFT);
        fDisplay.setVerticalAlignment(SwingConstants.TOP);

        contentView.add(fDisplay, BorderLayout.CENTER);
        fFrame.setContentPane(contentView);
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        fFrame.setLocation((screen.width - fExtent.width) / 2, (screen.height - fExtent.height) / 2);   // center
        fFrame.addComponentListener(new ComponentListener() {
            @Override
            public void componentResized(ComponentEvent e) {
                Dimension currentDimension = fDisplay.getSize();
                SqueakLogger.log_D("fDisplay resized width: " + currentDimension.width + "height: " + currentDimension.height);
                fExtent.setSize(currentDimension.width, currentDimension.height);
            }

            @Override
            public void componentMoved(ComponentEvent e) {

            }

            @Override
            public void componentShown(ComponentEvent e) {

            }

            @Override
            public void componentHidden(ComponentEvent e) {

            }
        });

        fMouseStatus = new MouseStatus((SqueakVM) fVMSemaphore);
        fDisplay.addMouseMotionListener(fMouseStatus);
        fDisplay.addMouseListener(fMouseStatus);

        fDisplay.setFocusable(true);    // enable keyboard input
        fKeyboardQueue = new KeyboardQueue((SqueakVM) fVMSemaphore);
        fDisplay.addKeyListener(fKeyboardQueue);

        inputNotifyThread = new InputNotifyThread((SqueakVM) fVMSemaphore);
        inputNotifyThread.start();

        fDisplay.setOpaque(true);
        fDisplay.getRootPane().setDoubleBuffered(false);    // prevents losing intermediate redraws (how?!)
    }

    public JFrame getFrame() {
        return fFrame;
    }

    public void setBits(int rawBits[], int depth) {
        fDepth = depth;
        fDisplay.setIcon(createDisplayAdapter(rawBits));
    }

    byte[] getBits() {
        return fDisplayBits;
    }

    protected Icon createDisplayAdapter(int storage[]) {
        DataBuffer buf = new DataBufferInt(storage, (fExtent.height * fExtent.width) * fDepth);
        SampleModel sm = new MultiPixelPackedSampleModel(DataBuffer.TYPE_INT, fExtent.width, fExtent.height, fDepth);
        WritableRaster raster = Raster.createWritableRaster(sm, buf, new Point(0, 0));
        Image image;
        if (fDepth == 1) {
            // Black&White color model
            ColorModel colorModel = ScreenUtils.getBlackWhiteModel();
            image = new BufferedImage(colorModel, raster, colorModel.isAlphaPremultiplied(), null);
        } else {
            // 8-bit color model
            ColorModel colorModel = ScreenUtils.get256ColorModel();
            image = new BufferedImage(colorModel, raster, false, null);
        }
        // TODO adding support for more color depth
        return new ImageIcon(image);
    }

    public void open() {
        fFrame.pack();
        fFrame.setVisible(true);
        if (WITH_HEARTBEAT) {
            fHeartBeat = new Timer(1000 / FPS /* ms */, new ActionListener() {
                public void actionPerformed(ActionEvent evt) {
                    // Swing timers execute on EHT
                    if (fScreenChanged) {
                        // could use synchronization, but lets rather paint too often
                        fScreenChanged = false;
                        Dimension extent = fDisplay.getSize();
                        fDisplay.repaint(0, 0, extent.width, extent.height);
                    }
                }
            });
            fHeartBeat.start();
        }
    }

    @Deprecated
    public void close() {
        fFrame.setVisible(false);
        fFrame.dispose();
        if (WITH_HEARTBEAT)
            fHeartBeat.stop();
    }

    public void redisplay(boolean immediately, Rectangle area) {
        redisplay(immediately, area.x, area.y, area.width, area.height);
    }

    public void redisplay(boolean immediately, final int cornerX, final int cornerY, final int width, final int height) {
        fScreenChanged = true;
    }

    @Deprecated
    public void redisplay(boolean immediately) {
        fDisplay.repaint();
        fScreenChanged = true;
    }

    @Deprecated
    protected boolean scheduleRedisplay(boolean immediately, Runnable trigger) {
        if (immediately) {
            try {
                SwingUtilities.invokeAndWait(trigger);
                return true;
            } catch (InterruptedException e) {
                logRedisplayException(e);
            } catch (InvocationTargetException e) {
                logRedisplayException(e);
            }
            return false;
        } else {
            SwingUtilities.invokeLater(trigger);
            return true;
        }
    }

    // extension point, default is ignorance
    protected void logRedisplayException(Exception e) {
    }

    private final static int Squeak_CURSOR_WIDTH = 16;
    private final static int Squeak_CURSOR_HEIGHT = 16;

    private final static byte C_WHITE = 0;
    private final static byte C_BLACK = 1;
    private final static byte C_TRANSPARENT = 2;
    private final static byte kCursorComponentX[] = new byte[]{-1, 0, 0};
    private final static byte kCursorComponentA[] = new byte[]{-1, -1, 0};

    protected Image createCursorAdapter(byte bits[], byte mask[]) {
        int bufSize = Squeak_CURSOR_HEIGHT * Squeak_CURSOR_WIDTH;
        DataBuffer buf = new DataBufferByte(new byte[bufSize], bufSize);
        // unpack samples and mask to bytes with transparency:
        int p = 0;
        for (int row = 0; row < Squeak_CURSOR_HEIGHT; row++) {
            for (int x = 0; x < 2; x++) {
                for (int col = 0x80; col != 0; col >>>= 1) {
                    if ((mask[(row * 4) + x] & col) != 0)
                        buf.setElem(p++, (bits[(row * 4) + x] & col) != 0 ? C_BLACK : C_WHITE);
                    else
                        buf.setElem(p++, C_TRANSPARENT);
                }
            }
        }
        SampleModel sm = new SinglePixelPackedSampleModel(DataBuffer.TYPE_BYTE, Squeak_CURSOR_WIDTH, Squeak_CURSOR_HEIGHT, new int[]{255});
        IndexColorModel cm = new IndexColorModel(8, 3, kCursorComponentX, kCursorComponentX, kCursorComponentX, kCursorComponentA);
        WritableRaster raster = Raster.createWritableRaster(sm, buf, new Point(0, 0));
        return new BufferedImage(cm, raster, false, null);
    }

    protected byte[] extractBits(byte bitsAndMask[], int offset) {
        final int n = bitsAndMask.length / 2;  // 32 bytes -> 8 bytes
        byte answer[] = new byte[n];
        for (int i = 0; i < n; i++) {
            // convert incoming little-endian words to bytes:
            answer[i] = bitsAndMask[offset + i];
        }
        return answer;
    }

    public void setCursor(byte imageAndMask[], int BWMask) {
        int n = imageAndMask.length;
        for (int i = 0; i < n / 2; i++) {
            imageAndMask[i] ^= BWMask; // reverse cursor bits all the time
            imageAndMask[i + (n / 2)] ^= BWMask;  // reverse transparency if display reversed
        }
        Toolkit tk = Toolkit.getDefaultToolkit();
        Dimension cx = tk.getBestCursorSize(Squeak_CURSOR_WIDTH, Squeak_CURSOR_HEIGHT);
        Cursor c;
        if (cx.width == 0 || cx.height == 0) {
            c = Cursor.getDefaultCursor();
        } else {
            Image ci = createCursorAdapter(extractBits(imageAndMask, 0), extractBits(imageAndMask, Squeak_CURSOR_HEIGHT * 4));
            c = tk.createCustomCursor(ci, new Point(0, 0), "Smalltalk-78 cursor");
        }
        fDisplay.setCursor(c);
    }

    public Dimension getExtent() {
        return fDisplay.getSize();
    }

    public void setExtent(Dimension extent) {
        fDisplay.setSize(extent);
        fFrame.setSize(extent);
    }

    public Point getLastMousePoint() {
        return new Point(fMouseStatus.fX, fMouseStatus.fY);
    }

    public int getLastMouseButtonStatus() {
        return (fMouseStatus.fButtons & 7) | fKeyboardQueue.modifierKeys();
    }

    public void setMousePoint(int x, int y) {
        Point origin = fDisplay.getLocationOnScreen();
        x += origin.x;
        y += origin.y;
        try {
            Robot robot = new Robot();
            robot.mouseMove(x, y);
        } catch (AWTException e) {
            // ignore silently?
            System.err.println("Mouse move to " + x + "@" + y + " failed.");
        }
    }

    public int keyboardPeek() {
        return fKeyboardQueue.peek();
    }

    public int keyboardNext() {
        //System.err.println("character code="+fKeyboardQueue.peek());
        return fKeyboardQueue.next();
    }

    public void exit() {
        inputNotifyThread.quit();
        fFrame.setVisible(false);
        fFrame.dispose();
        if (WITH_HEARTBEAT) {
            fHeartBeat.stop();
        }
        System.exit(1);
    }
}
