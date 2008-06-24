/*
SqueakImage.java
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

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.lang.Integer;
import java.lang.Double;
import java.lang.ref.*;
import JSqueak.*;

/**
 * @author Daniel Ingalls
 *
 * A SqueakImage represents the complete state of a running Squeak.
 * This implemenatation uses Java objects (see SqueakObject) for all Squeak objects,
 * with direct pointers between them.  Enumeration is supported by objectTable,
 * which points weakly to all objects.  SmallIntegers are modelled by Java Integers.
 *
 * Some care is taken in reclaiming OT slots, to preserve the order of creation of objects,
 * as this matters for Squeak weak objects, should we ever support them.
 */

public class SqueakImage {
    SqueakVM vm;
    WeakReference[] objectTable;
    int otMaxUsed;
    int otMaxOld;
    SqueakObject specialObjectsArray;
    int lastHash;
    int lastOTindex;

    void bindVM(SqueakVM theVM) {vm= theVM; }

    public SqueakImage(InputStream raw) throws IOException {
	loaded(raw);
	}
	
    public SqueakImage(File fn) throws IOException {
	loaded(fn);
	}
	
    public void save(File fn) throws IOException {
	BufferedOutputStream fp= new BufferedOutputStream(new FileOutputStream(fn));
	GZIPOutputStream gz= new GZIPOutputStream(fp);
	DataOutputStream ser= new DataOutputStream(gz);
	writeImage(ser);
	ser.flush();
	ser.close(); }

    private void loaded(InputStream raw) throws IOException {
	BufferedInputStream fp= new BufferedInputStream(raw);
	GZIPInputStream gz= new GZIPInputStream(fp);
	DataInputStream ser= new DataInputStream(gz);
	readImage(ser); }

    private void loaded(File fn) throws IOException {
	FileInputStream unbuffered= new FileInputStream(fn);
	loaded(unbuffered);
	unbuffered.close(); }
	
    boolean bulkBecome(Object[] fromPointers, Object[] toPointers, boolean twoWay) {
	int n= fromPointers.length;
        Object p, ptr, body[], mut;
        SqueakObject obj;
        if(n != toPointers.length) return false;
        Hashtable mutations= new Hashtable(n*4*(twoWay?2:1));
	for(int i=0; i<n; i++) {
            p= fromPointers[i];
            if(!(p instanceof SqueakObject)) return false;  //non-objects in from array
            if(mutations.get(p) != null) return false; //repeated oops in from array
            else mutations.put(p,toPointers[i]); }
	if(twoWay) {
            for(int i=0; i<n; i++) {
                p= toPointers[i];
            if(!(p instanceof SqueakObject)) return false;  //non-objects in to array
                if(mutations.get(p) != null) return false; //repeated oops in to array
                else mutations.put(p,fromPointers[i]); } }
        for(int i=0; i<=otMaxUsed; i++) {
            // Now, for every object...
            obj= (SqueakObject)objectTable[i].get();
            if(obj != null) { // mutate the class
                mut= (SqueakObject)mutations.get(obj.sqClass);
                if(mut != null) obj.sqClass= mut; 
                if((body= obj.pointers) != null) { // and mutate body pointers
                   for(int j=0; j<body.length; j++) {
                        ptr= body[j];
                        mut= mutations.get(ptr);
                        if(mut != null) body[j]= mut; } }
            } }
        return true; }


    //Enumeration...
    SqueakObject nextInstance(int startingIndex, SqueakObject sqClass) {
        //if sqClass is null, then find next object, else find next instance of sqClass
        for(int i=startingIndex; i<=otMaxUsed; i++) { // For every object...
            SqueakObject obj= (SqueakObject)objectTable[i].get();
            if(obj != null && (sqClass==null | obj.sqClass == sqClass)) {
                lastOTindex= i; // save hint for next scan
                return obj; } }
        return vm.nilObj; } // Return nil if none found
    
    int otIndexOfObject(SqueakObject lastObj) {
        // hint: lastObj should be at lastOTindex
        SqueakObject obj= (SqueakObject)objectTable[lastOTindex].get(); 
        if(lastOTindex<=otMaxUsed && obj==lastObj) return lastOTindex;
        else {for(int i=0; i<=otMaxUsed; i++) { // Alas no; have to find it again...
            obj= (SqueakObject)objectTable[i].get();
            if(obj == lastObj) return i; } }
        return -1; } //should not happen
        
	
    private final static int OTMinSize= 30000;
    private final static int OTMaxSize= 60000;
    private final static int OTGrowSize= 10000;
	
    public short registerObject (SqueakObject obj) {
        //All enumerable objects must be registered
        if ((otMaxUsed+1) >= objectTable.length)
            if (!getMoreOops(OTGrowSize))
                throw new RuntimeException("Object table has reached capacity");
        objectTable[++otMaxUsed]= new WeakReference(obj);
	lastHash= 13849 + (27181 * lastHash);
        return (short) (lastHash & 0xFFF); }
	
    private boolean getMoreOops(int request) {
        int nullCount;
        int startingOtMaxUsed= otMaxUsed;
        for(int i=0; i<5; i++) {
            if(i==2) vm.clearCaches(); //only flush caches after two tries
            partialGC();
            nullCount= startingOtMaxUsed - otMaxUsed;
            if(nullCount >= request) return true;
          }
        // Sigh -- really need more space...
        int n= objectTable.length;
        if (n+request > OTMaxSize) {
            fullGC();
            return false; }
        System.out.println("Squeak: growing to " + (n+request) + " objects...");
        WeakReference newTable[]= new WeakReference[n+request];
        System.arraycopy(objectTable, 0, newTable, 0, n);
        objectTable= newTable;
        return true; }
    
    int partialGC() {
        System.gc();
        otMaxUsed=reclaimNullOTSlots(otMaxOld);
        return spaceLeft(); }

    int spaceLeft() {
        return (int)Math.min(Runtime.getRuntime().freeMemory(),(long)vm.maxSmallInt); }

    int fullGC() {
        vm.clearCaches();
        for(int i=0; i<5; i++) partialGC();
        otMaxUsed=reclaimNullOTSlots(0);
        otMaxOld= Math.min(otMaxOld,otMaxUsed);
        return spaceLeft(); }

    private int reclaimNullOTSlots(int start) {
        // Java GC will null out slots in the weak Object Table.
        // This procedure compacts the occupied slots (retaining order),
        // and returns a new value for otMaxUsed.
        // If start=0, all are scanned (like full gc);
        // if start=otMaxOld it will skip the old objects (like gcMost).
        int oldOtMaxUsed= otMaxUsed;
        int writePtr= start;
        for(int readPtr= start; readPtr<=otMaxUsed; readPtr++)
            if(objectTable[readPtr].get() != null)
                objectTable[writePtr++]=objectTable[readPtr];
        if(writePtr==start) return oldOtMaxUsed;
        return writePtr-1; }
    
    
    
    private void writeImage (DataOutput ser) {} // Later...

    private void readImage(DataInput in) throws IOException {
//System.err.println("-3.0" + Double.doubleToLongBits(-3.0d));
System.out.println("Start reading at " + System.currentTimeMillis());
	objectTable= new WeakReference[OTMinSize];
        otMaxUsed= -1;
        Hashtable oopMap= new Hashtable(30000);
        boolean doSwap= false;
        int version= intFromInputSwapped(in, doSwap);
        if (version != 6502) {
            version= swapInt(version);
            if (version != 6502)
                throw new IOException("bad image version");
            doSwap= true;
            }
        System.err.println("version passes with swap= " + doSwap);
        int headerSize= intFromInputSwapped(in, doSwap);
        int endOfMemory= intFromInputSwapped(in, doSwap); //first unused location in heap
        int oldBaseAddr= intFromInputSwapped(in, doSwap); //object memory base address of image
        int specialObjectsOopInt= intFromInputSwapped(in, doSwap); //oop of array of special oops
        lastHash= intFromInputSwapped(in, doSwap); //Should be loaded from, and saved to the image header
        int savedWindowSize= intFromInputSwapped(in, doSwap);
        int fullScreenFlag= intFromInputSwapped(in, doSwap);
        int extraVMMemory= intFromInputSwapped(in, doSwap);
        in.skipBytes(headerSize - (9*4)); //skip to end of header

        for (int i= 0; i<endOfMemory;) {
            int nWords= 0;
            int classInt= 0;
            int[] data;
            int format= 0;
            int hash= 0;
            int header= intFromInputSwapped(in, doSwap);
            switch (header & Squeak.HeaderTypeMask) {
                case Squeak.HeaderTypeSizeAndClass:
                    nWords= header>>2;
                    classInt= intFromInputSwapped(in, doSwap) - Squeak.HeaderTypeSizeAndClass;
                    header= intFromInputSwapped(in, doSwap);
                    i= i+12;
                    break;
                case Squeak.HeaderTypeClass:
                    classInt= header - Squeak.HeaderTypeClass;
                    header= intFromInputSwapped(in, doSwap);
                    i= i+8;
                    nWords= (header>>2) & 63;
                    break;
                case Squeak.HeaderTypeFree:
                    throw new IOException("Unexpected free block");
                case Squeak.HeaderTypeShort:
                    i= i+4;
                    classInt= (header>>12) & 31; //compact class index
                    //Note classInt<32 implies compact class index
                    nWords= (header>>2) & 63;
                    break;
                }
            int baseAddr= i - 4; //0-rel byte oop of this object (base header)
            nWords--;  //length includes base header which we have already read
            format= ((header>>8) & 15);
            hash= ((header>>17) & 4095);

            // Note classInt and data are just raw data; no base addr adjustment and no Int conversion
            data= new int[nWords];
            for (int j= 0; j<nWords; j++)
            data[j]= intFromInputSwapped(in, doSwap);
            i= i+(nWords*4);

            SqueakObject javaObject= new SqueakObject(new Integer (classInt),(short)format,(short)hash,data);
            registerObject(javaObject);
            //oopMap is from old oops to new objects
            //Why can't we use ints as keys??...
            oopMap.put(new Integer(baseAddr+oldBaseAddr),javaObject);
            }
        //Temp version of spl objs needed for makeCCArray; not a good object yet
        specialObjectsArray= (SqueakObject)(oopMap.get(new Integer(specialObjectsOopInt)));
        Integer[] ccArray= makeCCArray(oopMap,specialObjectsArray);
        int oldOop= specialObjectsArray.oldOopAt(Squeak.splOb_ClassFloat);
        SqueakObject floatClass= ((SqueakObject) oopMap.get(new Integer(oldOop)));
System.out.println("Start installs at " + System.currentTimeMillis());
        for (int i= 0; i<otMaxUsed; i++) {
            // Don't need oldBaseAddr here**
            ((SqueakObject) objectTable[i].get()).install(oopMap,ccArray,floatClass);
            }
System.out.println("Done installing at " + System.currentTimeMillis());
        //Proper version of spl objs -- it's a good object
        specialObjectsArray= (SqueakObject)(oopMap.get(new Integer(specialObjectsOopInt)));
        otMaxOld= otMaxUsed; }
    
    private int intFromInputSwapped (DataInput in, boolean doSwap) throws IOException {
        // Return an int from stream 'in', swizzled if doSwap is true
        if (doSwap) return swapInt(in.readInt());
            else return in.readInt(); }
        
    private int swapInt (int toSwap) {
        // Return an int with byte order reversed
        int incoming= toSwap;
        int outgoing= 0;
        for (int i= 0; i<4; i++) {
                int lowByte= incoming & 255;
                outgoing= (outgoing<<8) + lowByte;
                incoming= incoming>>8; }
        return outgoing; }
        
    private Integer[] makeCCArray(Hashtable oopMap, SqueakObject splObs) {
        //Makes an aray of the complact classes as oldOops (still need to be mapped)
        int oldOop= splObs.oldOopAt(Squeak.splOb_CompactClasses);
        SqueakObject compactClassesArray= ((SqueakObject) oopMap.get(new Integer(oldOop)));
        Integer[] ccArray= new Integer[31];
        for (int i= 0; i<31; i++) {
            ccArray[i]= new Integer (compactClassesArray.oldOopAt(i)); }
        return ccArray; }
    
    }
