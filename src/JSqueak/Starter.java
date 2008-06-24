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
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;

//import joe.framework.Simulation;
//import joe.framework.Executable;
import JSqueak.*;

public class Starter {
	
    public static SqueakImage locateStartableImage(String args[]) throws IOException {
		String name;
		if (args.length == 0) {
			name= "mini.image.gz";
                        //File saved= new File(name);
			//if (saved.exists()) return new SqueakImage(saved);
			// and only if no image name was given
                        InputStream ims= Starter.class.getResourceAsStream("mini.image.gz");
                        System.out.println("BINGO: " + ims);
                        if (ims != null) return new SqueakImage(ims);
                } else {
			File saved= new File(name= args[0]);
			if (saved.exists())
				return new SqueakImage(saved);
		}
		System.err.println("Image " + name + " not found.");
		return null;
	}

	/**
	 * @param args first arg may specify image file name
	 */
    public static void main(String[] args) throws IOException, NullPointerException, java.lang.ArrayIndexOutOfBoundsException {
        SqueakVM.initSmallIntegerCache();
        SqueakImage img= locateStartableImage(args);
        SqueakVM vm= new SqueakVM(img);
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
