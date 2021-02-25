package JSqueak.input;

import JSqueak.SqueakVM;

/**
 * InputNotifyThread will notify VM input events
 * at a fixed-frequency
 */
public class InputNotifyThread extends Thread {

    private final SqueakVM squeakVM;

    public InputNotifyThread(SqueakVM vm) {
        this.squeakVM = vm;
    }

    boolean running = true;

    @Override
    public void run() {
        while (running) {
            synchronized (SqueakVM.inputLock) {
                squeakVM.setScreenEvent(true);
                SqueakVM.inputLock.notify();
            }

            try {
                Thread.sleep(0, 100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            synchronized (SqueakVM.inputLock) {
                squeakVM.setScreenEvent(false);
            }

            try {
                Thread.sleep(33);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        System.err.println("Quit InputNotifyThread");
    }

    public void quit() {
        this.running = false;
    }
}
