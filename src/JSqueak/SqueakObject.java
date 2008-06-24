/*
SqueakObject.java
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
import java.util.*;
import java.lang.Object;

/**
 * @author Daniel Ingalls
 *
 * Squeak objects are modelled by Java objects with separate binary and pointer data.
 * Later this could be optimized for objects that have only one or the other, but for
 * now it is simple, and handles the inhomogeneous case of CompiledMethods nicely.
 *
 * Weak fields are not currently supported.  The plan for doing this would be
 * to make those objects a subclass, and put pointers in a WeakField.  We would 
 * need to replace all patterns of obj.pointers with an access function.
 * Then we would associate a finalization routine with those pointers.
 */

public class SqueakObject {//Later make variants for common formats
    short hash;        //12-bit Squeak hash
    short format;      // 4-bit Squeak format
    Object sqClass;  //squeak class
    Object[] pointers; //pointer fields; fixed as well as indexable
    Object bits;       //indexable binary data (bytes or ints)
	
    SqueakObject(Integer cls, int fmt, int hsh, int[] imageData) {
        //Initial creation from SqueakImage, with unmapped data
        sqClass= cls;
        format= (short)fmt;
        hash= (short)hsh;
        bits= imageData;}

    SqueakObject(SqueakImage img) {
        //Creation of stub object (no pointers or bits)
        hash= img.registerObject(this);}

    SqueakObject(SqueakImage img, SqueakObject cls, int indexableSize, SqueakObject filler) {
        //Creation of objects from Squeak
        this(img);
        sqClass= cls;
        int instSpec= SqueakVM.intFromSmall(cls.getPointerI(Squeak.Class_format));
        int instSize= ((instSpec>>1) & 0x3F) + ((instSpec>>10) & 0xC0) - 1; //0-255
        format= ((short) ((instSpec>>7) & 0xF)); //This is the 0-15 code

        if(format<8) {
            if (format!=6) {
                pointers= new Object[instSize+indexableSize];
                Arrays.fill(pointers,filler);}
            else
                if(indexableSize>=0)
                    bits= new int[indexableSize];
            }
        else
            bits= new byte[indexableSize]; //Methods require further init of pointers
        }

//      Definition of Squeak's format code...
//
//      Pointers only...
//        0      no fields
//        1      fixed fields only (all containing pointers)
//        2      indexable fields only (all containing pointers)
//        3      both fixed and indexable fields (all containing pointers)
//        4      both fixed and indexable weak fields (all containing pointers).
//        5      unused
//      Bits only...
//        6      indexable word fields only (no pointers)
//        7      unused
//        8-11   indexable byte fields only (no pointers) (low 2 bits are low 2 bits of size)
//      Pointer and bits (CompiledMethods only)...
//       12-15   compiled methods:
//               # of literal oops specified in method header,
//               followed by indexable bytes (same interpretation of low 2 bits as above)


    //General access
    public SqueakObject getSqClass() {
        return (SqueakObject) sqClass;}
        
    public Object getPointer(int zeroBasedIndex) {
	return pointers[zeroBasedIndex];}
	
    public SqueakObject getPointerNI(int zeroBasedIndex) {
	//Returns only SqueakObjects, not Integers
        return (SqueakObject) pointers[zeroBasedIndex];}
	
    public Integer getPointerI(int zeroBasedIndex) {
	//Returns only SmallIntegers
        return (Integer) pointers[zeroBasedIndex];}
	
    public void setPointer(int zeroBasedIndex, Object aPointer) {
	pointers[zeroBasedIndex]= aPointer;}
	
    public int pointersSize() {
	return pointers==null? 0 : pointers.length;}
    
    public int bitsSize() {
	if (bits==null) return 0;
        if (bits instanceof byte[]) return ((byte[])bits).length;
        if (bits instanceof Double) return 2;
        return ((int[])bits).length;}
	
    public int instSize() {//same as class.classInstSize, but faster from format
        if(format>4 || format==2) return 0; //indexable fields only
        if(format<2) return pointers.length; //indexable fields only
        return ((SqueakObject)sqClass).classInstSize();} //0-255

    public int classInstSize() {
        int instSpec= SqueakVM.intFromSmall(this.getPointerI(Squeak.Class_format));
        return ((instSpec>>1) & 0x3F) + ((instSpec>>10) & 0xC0) - 1;} //0-255

    public SqueakObject classGetName() {
        return this.getPointerNI(Squeak.Class_name);}

    SqueakObject cloneIn(SqueakImage img) {
        //Need to get new hash, OT entry...
        SqueakObject clone= new SqueakObject(img);
        clone.copyStateFrom(this);
        return clone;}
    
    private void copyStateFrom(SqueakObject other) {
        sqClass= other.sqClass;
        format= other.format;
        pointers= (Object[])other.pointers.clone();
        Object otherBits= other.bits;
        if(otherBits==null) return;
        if(otherBits instanceof byte[])
            bits= ((byte[])other.bits).clone();
        else if(otherBits instanceof int[])
            bits= ((int[])other.bits).clone();}
        
    double getFloatBits() { // isn't this slow?'
        return ((Double)bits).doubleValue(); }
	
    void setFloatBits(double value) {
	bits= new Double(value); }
	

    //CompiledMethods
    public int methodHeader() {return ((Integer)getPointer(0)).intValue();}
	
    public int methodNumLits() {
	return (methodHeader()>>9)&0xFF;}
	
    public int methodNumArgs() {
	return (methodHeader()>>24)&0xF;}
	
    public int methodPrimitiveIndex() {
	int primBits= (methodHeader())&0x300001FF;
	if(primBits > 0x1FF) return (primBits & 0x1FF) + (primBits >> 19);
        else return primBits;}

    public SqueakObject methodClassForSuper() {//assn found in last literal
        SqueakObject assn= getPointerNI(methodNumLits());
        return assn.getPointerNI(Squeak.Assn_value);}
        
    public boolean methodNeedsLargeFrame() {
	return (methodHeader() & 0x20000) > 0; }

    public void methodAddPointers(Object[] headerAndLits) {
	pointers= headerAndLits; }

    public int methodTempCount() {
	return (methodHeader()>>18) & 63; }

    public Object methodGetLiteral(int zeroBasedIndex) {
	return getPointer(1+zeroBasedIndex); }	// step over header
	
    public SqueakObject methodGetSelector(int zeroBasedIndex) {
	return getPointerNI(1+zeroBasedIndex); }	// step over header
	
    public void methodSetLiteral(int zeroBasedIndex, Object rawValue) {
	setPointer(1+zeroBasedIndex, rawValue); }	// step over header

    
    //Methods below here are only used for reading the Squeak image format
    public void install(Hashtable oopMap, Integer[] ccArray, SqueakObject floatClass) {
        //Install this object by decoding format, and rectifying pointers
        int ccInt= ((Integer)sqClass).intValue();
        if ((ccInt>0) && (ccInt<32))
            sqClass= oopMap.get(ccArray[ccInt-1]);
        else
            sqClass= oopMap.get(sqClass);
        int nWords= ((int[]) bits).length;
        if(format<5) {
            //Formats 0...4 -- Pointer fields
            pointers= decodePointers(nWords,((int[])bits),oopMap);
            bits= null; }
        else {
            if (format>=12) {
                //Formats 12-15 -- CompiledMethods both pointers and bits
                int methodHeader= ((int[])bits)[0];
                int numLits= (methodHeader>>10)&255;
                pointers= decodePointers(numLits+1,((int[])bits),oopMap); //header+lits
                bits= decodeBytes(nWords-(numLits+1),((int[])bits),numLits+1,format&3); }
            else if (format>=8) {
                //Formats 8..11 -- ByteArrays (and Strings)
                bits= decodeBytes(nWords,((int[])bits),0,format&3); }
                //Format 6 word objects are already OK (except Floats...)
                else if(sqClass==floatClass) {
                    //Floats need two ints to be converted to double
                    long longBits= (((long)((int[])bits)[0])<<32) | (((long)((int[])bits)[0])&0xFFFFFFFF);
//System.err.println();
//System.err.println(((int[])bits)[0] + " " + ((int[])bits)[1] + " -> " + longBits);
                    bits= new Double(Double.longBitsToDouble(longBits)); }
//System.err.println((Double)bits + " " + Double.doubleToRawLongBits(((Double)bits).doubleValue()));
                }
        }

    private Object[] decodePointers(int nWords,int[]theBits,Hashtable oopMap) {
        //Convert small ints and look up object pointers in oopMap
        Object[] ptrs= new Object[nWords];
        for (int i=0; i<nWords; i++) {
            int oldOop= theBits[i];
            if ((oldOop&1)==1)
                ptrs[i]= SqueakVM.smallFromInt(oldOop>>1);
            else
                ptrs[i]= oopMap.get(new Integer (oldOop));
                }
        return ptrs;
        }

    private byte[] decodeBytes(int nWords,int[]theBits,int wordOffset,int fmtLoBits) {
        //Adjust size for low bits and extract bytes from ints
        int nBytes= (nWords*4) - (format&3);
        byte[]newBits= new byte[nBytes];
        int wordIx= wordOffset;
        int fourBytes= 0;
        for (int i=0; i<nBytes; i++) {
            if ((i&3)==0)
                fourBytes= theBits[wordIx++];
            int pickByte= (fourBytes>>(8*(3-(i&3))))&255;
            if (pickByte>=128)
                pickByte= pickByte-256;
            newBits[i]= (byte) pickByte;
            }
        return newBits; }

    public int oldOopAt(int zeroBasedOffset) {
        return ((int[]) bits)[zeroBasedOffset]; }
	
    public String asString() {	// debugging only: if body consists of bytes, make a Java String from them
	if (bits != null && bits instanceof byte[]) {
		if(pointers != null) return "a CompiledMethod";
                else return new String((byte[])bits); }
            else { SqueakObject itsClass= this.getSqClass();
                if(itsClass.pointersSize() >= 9)
                    return "a " + itsClass.classGetName().asString();
                else return "Class " + this.classGetName().asString(); } }

    public String toString() {
        return this.asString(); }


}
