/*
Starter.java
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

package JSqueak;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

public class Starter {
    /**
     * The file name of the mini image.
     */
    private static final String MINI_IMAGE = "mini.image.gz";

    /**
     * Locate a startable image as a resource.
     */
    private static SqueakImage locateStartableImage() throws IOException {
        //File saved= new File( pathname );
        //if (saved.exists()) return new SqueakImage(saved);
        // and only if no image name was given
        URL imageUrl = Starter.class.getResource(MINI_IMAGE);
        if ("file".equals(imageUrl.getProtocol()))
            return new SqueakImage(new File(imageUrl.getPath()));

        InputStream ims = Starter.class.getResourceAsStream(MINI_IMAGE);
        if (ims != null)
            return new SqueakImage(ims);

        throw new FileNotFoundException("Cannot locate resource " + MINI_IMAGE);
    }

    /**
     * Locate a startable image at a specified path.
     */
    private static SqueakImage locateSavedImage(String pathname) throws IOException {
        File saved = new File(pathname);
        if (saved.exists())
            return new SqueakImage(saved);

        throw new FileNotFoundException("Cannot locate image " + pathname);
    }

    /**
     * @param args first arg may specify image file name
     */
    public static void main(String[] args) throws IOException, NullPointerException, java.lang.ArrayIndexOutOfBoundsException {
        SqueakVM.initSmallIntegerCache();
        SqueakImage img = args.length > 0 ? locateSavedImage(args[1])
                : locateStartableImage();
        SqueakVM vm = new SqueakVM(img);
        vm.run();
    }

    //Simulation sim= new Simulation(vm);
    //sim.run();
    //if (sim.getState() == Executable.CANCELLED) {
    //    Throwable t= (Throwable)sim.getReturnValue();
    //    t.printStackTrace(System.err);
    //}
    //SqueakVM vm= locateStartableImage(args);
    //if (vm != null) {
    //vm.shutdown();
    //}
}
