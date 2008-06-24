/*
SqueakPrimitiveHandler.java
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
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * @author Daniel Ingalls
 *
 * Implements the indexed primitives for the Squeak VM.
 */
class SqueakPrimitiveHandler {
    private SqueakVM vm;
    private SqueakImage image;
    boolean success;
    private Screen theDisplay;
    int[] displayBitmap;
    int displayRaster;
    byte[] displayBitmapInBytes;
    BitBlt bitbltTable;
    int BWMask= 0;


    // Its purpose of the at-cache is to allow fast (bytecode) access to at/atput code
    // without having to check whether this object has overridden at, etc.
    int atCacheSize= 32; // must be power of 2
    int atCacheMask= atCacheSize-1; //...so this is a mask
    AtCacheInfo[] atCache;
    AtCacheInfo[] atPutCache;
    AtCacheInfo nonCachedInfo;

    SqueakPrimitiveHandler(SqueakVM theVM) {
        vm= theVM;
        image= vm.image;
        bitbltTable= new BitBlt(vm);
        initAtCache(); }

    
    class AtCacheInfo {
        SqueakObject array;
        int size;
        int ivarOffset;
        boolean convertChars; }
    void initAtCache() {
        atCache= new AtCacheInfo[atCacheSize];
        atPutCache= new AtCacheInfo[atCacheSize];
        nonCachedInfo= new AtCacheInfo();
        for(int i= 0; i<atCacheSize; i++) {
            atCache[i]= new AtCacheInfo();
            atPutCache[i]= new AtCacheInfo();} }
    void clearAtCache() { //clear at-cache pointers (prior to GC)
        for(int i= 0; i<atCacheSize; i++) {
            atCache[i].array= null;
            atPutCache[i].array= null;} }
    AtCacheInfo makeCacheInfo(AtCacheInfo[] atOrPutCache, Object atOrPutSelector, SqueakObject array, boolean convertChars, boolean includeInstVars) {
        //Make up an info object and store it in the atCache or the atPutCache.
        //If it's not cacheable (not a non-super send of at: or at:put:)
        //then return the info in nonCachedInfo.
        //Note that info for objectAt (includeInstVars) will have
        //a zero ivarOffset, and a size that includes the extra instVars
        AtCacheInfo info;
        boolean cacheable= (vm.verifyAtSelector == atOrPutSelector) //is at or atPut
		&& (vm.verifyAtClass == array.getSqClass())         //not a super send
                && (array.format==3 && vm.isContext(array));        //not a context (size can change)
        if(cacheable) info= atOrPutCache[array.hashCode() & atCacheMask];
            else      info= nonCachedInfo;
        info.array= array;
        info.convertChars= convertChars; 
        if(includeInstVars) {
            info.size= Math.max(0,indexableSize(array)) + array.instSize();
            info.ivarOffset= 0; }
            else {
            info.size= indexableSize(array);
            info.ivarOffset= (array.format<6) ? array.instSize() : 0; }
        return info; }
    
        
    // Quick Sends from inner Interpreter
    boolean quickSendOther(Object rcvr, int lobits) {
        // QuickSendOther returns true if it succeeds
        success= true;
        switch (lobits) {
            case 0x0: return popNandPushIfOK(2,primitiveAt(true,true,false)); // at:
            case 0x1: return popNandPushIfOK(3,primitiveAtPut(true,true,false)); // at:put:
            case 0x2: return popNandPushIfOK(1,primitiveSize()); // size
            case 0x3: return false; // next
            case 0x4: return false; // nextPut
            case 0x5: return false; // atEnd
            case 0x6: return pop2andDoBoolIfOK(primitiveEq(vm.stackValue(1),vm.stackValue(0))); // ==
            case 0x7: return popNandPushIfOK(1,vm.getClass(vm.top())); // class
            case 0x8: return popNandPushIfOK(2,primitiveBlockCopy()); // blockCopy:
            case 0x9: return primitiveBlockValue(0); // value
            case 0xa: return primitiveBlockValue(1); // value:
            case 0xb: return false; // do:
            case 0xc: return false; // new
            case 0xd: return false; // new:
            case 0xe: return false; // x
            case 0xf: return false; // y
            default: return false;} }

    boolean primitiveEq(Object arg1, Object arg2) {// == must work for uninterned small ints
        if(vm.isSmallInt(arg1) && vm.isSmallInt(arg2))
            return ((Integer)arg1).intValue() == ((Integer)arg2).intValue();
        return arg1==arg2; }

    Object primitiveBitAnd() {
        int rcvr= stackPos32BitValue(1);
        int arg= stackPos32BitValue(0);
        if(!success) return vm.nilObj;
        return pos32BitIntFor(rcvr & arg); }

    Object primitiveBitOr() {
        int rcvr= stackPos32BitValue(1);
        int arg= stackPos32BitValue(0);
        if(!success) return vm.nilObj;
        return pos32BitIntFor(rcvr | arg); }
        
    Object primitiveBitXor() {
        int rcvr= stackPos32BitValue(1);
        int arg= stackPos32BitValue(0);
        if(!success) return vm.nilObj;
        return pos32BitIntFor(rcvr ^ arg); }
        
    Object primitiveBitShift() {
        int rcvr= stackPos32BitValue(1);
        int arg= stackInteger(0);
        if(!success) return vm.nilObj;
        return pos32BitIntFor(vm.safeShift(rcvr,arg)); }

    int doQuo(int rcvr, int arg) {
        if(arg == 0) {success= false; return 0; }
        if (rcvr > 0) {
            if (arg > 0) return rcvr / arg;
            else return 0 - (rcvr / (0 - arg)); }
	else {
            if (arg > 0) return 0 - ((0 - rcvr) / arg);
            else return (0 - rcvr) / (0 - arg); } }


    boolean doPrimitive(int index, int argCount) {
        success= true;
            switch (index) {	// 0..127
                case 1: return popNandPushIntIfOK(2,stackInteger(1)+stackInteger(0));  // Integer.add
                case 2: return popNandPushIntIfOK(2,stackInteger(1)-stackInteger(0));  // Integer.subtract
                case 3: return pop2andDoBoolIfOK(stackInteger(1)<stackInteger(0));  // Integer.less
                case 4: return pop2andDoBoolIfOK(stackInteger(1)>stackInteger(0));  // Integer.greater
                case 5: return pop2andDoBoolIfOK(stackInteger(1)<=stackInteger(0));  // Integer.leq
                case 6: return pop2andDoBoolIfOK(stackInteger(1)>=stackInteger(0));  // Integer.geq
                case 7: return pop2andDoBoolIfOK(stackInteger(1)==stackInteger(0));  // Integer.equal
                case 8: return pop2andDoBoolIfOK(stackInteger(1)!=stackInteger(0));  // Integer.notequal
                case 9: return popNandPushIntIfOK(2,vm.safeMultiply(stackInteger(1),stackInteger(0)));  // Integer.multiply *
                case 10: return popNandPushIntIfOK(2,vm.quickDivide(stackInteger(1),stackInteger(0)));  // Integer.divide /  (fails unless exact exact)
                case 11: return false; //popNandPushIntIfOK(2,doMod(stackInteger(1),stackInteger(0)));  // Integer.mod \\
                case 12: return popNandPushIntIfOK(2,vm.div(stackInteger(1),stackInteger(0)));  // Integer.div //
                case 13: return popNandPushIntIfOK(2,doQuo(stackInteger(1),stackInteger(0)));  // Integer.quo
                case 14: return popNandPushIfOK(2,primitiveBitAnd());  // SmallInt.bitAnd
                case 15: return popNandPushIfOK(2,primitiveBitOr());  // SmallInt.bitOr
                case 16: return popNandPushIfOK(2,primitiveBitXor());  // SmallInt.bitXor
                case 17: return popNandPushIfOK(2,primitiveBitShift());  // SmallInt.bitShift
                case 18: return primitiveMakePoint();
                case 40: return primitiveAsFloat();
                case 41: return popNandPushFloatIfOK(2,stackFloat(1)+stackFloat(0));  // Float +		// +
                case 42: return popNandPushFloatIfOK(2,stackFloat(1)-stackFloat(0));  // Float -	
                case 43: return pop2andDoBoolIfOK(stackFloat(1)<stackFloat(0));  // Float <
                case 44: return pop2andDoBoolIfOK(stackFloat(1)>stackFloat(0));  // Float >
                case 45: return pop2andDoBoolIfOK(stackFloat(1)<=stackFloat(0));  // Float <=
                case 46: return pop2andDoBoolIfOK(stackFloat(1)>=stackFloat(0));  // Float >=
                case 47: return pop2andDoBoolIfOK(stackFloat(1)==stackFloat(0));  // Float =
                case 48: return pop2andDoBoolIfOK(stackFloat(1)!=stackFloat(0));  // Float !=
                case 49: return popNandPushFloatIfOK(2,stackFloat(1)*stackFloat(0));  // Float.mul
                case 50: return popNandPushFloatIfOK(2,safeFDiv(stackFloat(1),stackFloat(0)));  // Float.div
                case 51: return primitiveTruncate();
                case 58: return popNandPushFloatIfOK(1,StrictMath.log(stackFloat(0)));  // Float.ln
	        case 60: return popNandPushIfOK(2,primitiveAt(false,false,false)); // basicAt:
                case 61: return popNandPushIfOK(3,primitiveAtPut(false,false,false)); // basicAt:put:
                case 62: return popNandPushIfOK(1,primitiveSize()); // size
	        case 63: return popNandPushIfOK(2,primitiveAt(false,true,false)); // basicAt:
                case 64: return popNandPushIfOK(3,primitiveAtPut(false,true,false)); // basicAt:put:
                case 68: return popNandPushIfOK(2,primitiveAt(false,false,true)); // Method.objectAt:
                case 69: return popNandPushIfOK(3,primitiveAtPut(false,false,true)); // Method.objectAt:put:
                case 70: return popNandPushIfOK(1,vm.instantiateClass(stackNonInteger(0),0)); // Class.new
                case 71: return popNandPushIfOK(2,primitiveNewWithSize()); // Class.new
                case 72: return popNandPushIfOK(2,primitiveArrayBecome(false));
                case 73: return popNandPushIfOK(2,primitiveAt(false,false,true)); // instVarAt:
                case 74: return popNandPushIfOK(3,primitiveAtPut(false,false,true)); // instVarAt:put:
                case 75: return popNandPushIfOK(1,primitiveHash()); // Class.identityHash
                case 77: return popNandPushIfOK(1,primitiveSomeInstance(stackNonInteger(0))); // Class.someInstance
                case 78: return popNandPushIfOK(1,primitiveNextInstance(stackNonInteger(0))); // Class.someInstance
                case 79: return popNandPushIfOK(3,primitiveNewMethod()); // Compiledmethod.new
                case 80: return popNandPushIfOK(2,primitiveBlockCopy()); // Context.blockCopy:
                case 81: return primitiveBlockValue(argCount); // BlockContext.value
                case 83: return vm.primitivePerform(argCount); // rcvr.perform:(with:)*
                case 84: return vm.primitivePerformWithArgs(vm.getClass(vm.stackValue(2))); // rcvr.perform:withArguments:
                case 85: return semaphoreSignal(); // Semaphore.wait
                case 86: return semaphoreWait(); // Semaphore.wait
                case 87: return processResume(); // Process.resume
                case 88: return processSuspend(); // Process.suspend
                case 89: return vm.clearMethodCache();  // selective
                case 90: return popNandPushIfOK(1,primitiveMousePoint()); // mousePoint
                case 96: if(argCount==0) return primitiveCopyBits((SqueakObject)vm.top(),0);
                                    else return primitiveCopyBits((SqueakObject)vm.stackValue(1),1);
                case 100: return vm.primitivePerformInSuperclass((SqueakObject)vm.top()); // rcvr.perform:withArguments:InSuperclass
                case 101: return beCursor(argCount); // Cursor.beCursor
                case 102: return beDisplay((SqueakObject)vm.top()); // DisplayScreen.beDisplay
                case 105: return popNandPushIfOK(5,primitiveStringReplace()); // string and array replace
                case 106: return popNandPushIfOK(1,makePointWithXandY(vm.smallFromInt(640),vm.smallFromInt(480))); // actualScreenSize
                case 107: return popNandPushIfOK(1,primitiveMouseButtons()); // Sensor mouseButtons
                case 108: return popNandPushIfOK(1,primitiveKbdNext()); // Sensor kbdNext
                case 109: return popNandPushIfOK(1,primitiveKbdPeek()); // Sensor kbdPeek
                case 110: return popNandPushIfOK(2,(vm.stackValue(1) == vm.stackValue(0))? vm.trueObj : vm.falseObj); // ==
                case 112: return popNandPushIfOK(1,vm.smallFromInt(image.spaceLeft())); // bytesLeft
                case 113: {System.exit(0); return true; }
                case 116: return vm.flushMethodCacheForMethod((SqueakObject)vm.top());
                case 119: return vm.flushMethodCacheForSelector((SqueakObject)vm.top());
                case 121: return popNandPushIfOK(1,makeStString("Macintosh HD:Users:danielingalls:Recent Squeaks:Old 3.3:mini.image")); //imageName
                case 122: {BWMask= ~BWMask; return true; }
                case 124: return popNandPushIfOK(2,registerSemaphore(Squeak.splOb_TheLowSpaceSemaphore));
                case 125: return popNandPushIfOK(2,setLowSpaceThreshold());
                case 128: return popNandPushIfOK(2,primitiveArrayBecome(true));
                case 129: return popNandPushIfOK(1,image.specialObjectsArray);
                case 130: return popNandPushIfOK(1,vm.smallFromInt(image.fullGC())); // GC
                case 131: return popNandPushIfOK(1,vm.smallFromInt(image.partialGC())); // GCmost
                case 134: return popNandPushIfOK(2,registerSemaphore(Squeak.splOb_TheInterruptSemaphore));
                case 135: return popNandPushIfOK(1,millisecondClockValue());
                case 136: return popNandPushIfOK(3,primitiveSignalAtMilliseconds()); //Delay signal:atMs:());
                case 137: return popNandPushIfOK(1,primSeconds()); //Seconds since Jan 1, 1901
                case 138: return popNandPushIfOK(1,primitiveSomeObject()); // Class.someInstance
                case 139: return popNandPushIfOK(1,primitiveNextObject(stackNonInteger(0))); // Class.someInstance
                case 142: return popNandPushIfOK(1,makeStString("Macintosh HD:Users:danielingalls:Recent Squeaks:Squeak VMs etc.:")); //vmPath
                case 148: return popNandPushIfOK(1,((SqueakObject)vm.top()).cloneIn(image)); //imageName
                case 149: return popNandPushIfOK(2,vm.nilObj); //getAttribute
                case 161: return popNandPushIfOK(1,charFromInt(58)); //path delimiter
                case 230: return primitiveYield(argCount); //yield for 10ms
                default: return false;}
        }
    
    boolean pop2andDoBoolIfOK(boolean bool) {
        vm.success= success;
        return vm.pushBoolAndPeek(bool); }
    
    boolean popNandPushIfOK(int nToPop, Object returnValue) {
        if( !success || returnValue == null) return false;
        vm.popNandPush(nToPop,returnValue);
        return true;}
    
    boolean popNandPushIntIfOK(int nToPop, int returnValue) {
        return popNandPushIfOK(nToPop, vm.smallFromInt(returnValue));}
    
    boolean popNandPushFloatIfOK(int nToPop, double returnValue) {
        if(!success) return false;
        return popNandPushIfOK(nToPop, makeFloat(returnValue));}
    
    int stackInteger(int nDeep) {
        return checkSmallInt(vm.stackValue(nDeep)); }

    int checkSmallInt(Object maybeSmall) { // returns an int and sets success
        if (vm.isSmallInt(maybeSmall)) return vm.intFromSmall((Integer)maybeSmall);
        success= false; return 0;}

    double stackFloat(int nDeep) {
        return checkFloat(vm.stackValue(nDeep)); }

    double checkFloat(Object maybeFloat) { // returns a float and sets success
        if (vm.getClass(maybeFloat)==vm.specialObjects[Squeak.splOb_ClassFloat])
            return ((SqueakObject)maybeFloat).getFloatBits();
        success= false; return 0.0d;}
    
    double safeFDiv(double dividend, double divisor) {
        if(divisor == 0.0d) {success= false; return 1.0d; }
        return dividend/divisor; }
    
    SqueakObject checkNonSmallInt(Object maybeSmall) { // returns a SqObj and sets success
        if (vm.isSmallInt(maybeSmall)) {success= false; return vm.nilObj; }
        return (SqueakObject) maybeSmall; }

    int stackPos32BitValue(int nDeep) {
        Object stackVal= vm.stackValue(nDeep);
        if (vm.isSmallInt(stackVal)) {
            int value= vm.intFromSmall((Integer) stackVal);
            if(value >= 0) return value;
            success= false; return 0; }
        if(!isA(stackVal,Squeak.splOb_ClassLargePositiveInteger))
            {success= false; return 0; }
        byte[] bytes= (byte[])((SqueakObject)stackVal).bits;
        int value= 0;
        for(int i=0; i<4; i++)
            value= value + ((bytes[i]&255)<<(8*i));
        return value; }

    Object pos32BitIntFor(int pos32Val) {
        // Return the 32-bit quantity as a positive 32-bit integer
        if(pos32Val >= 0) {
            Object smallInt= vm.smallFromInt(pos32Val);
            if(smallInt != null) return smallInt; }
        SqueakObject lgIntClass= (SqueakObject)vm.specialObjects[Squeak.splOb_ClassLargePositiveInteger];
        SqueakObject lgIntObj= vm.instantiateClass(lgIntClass,4);
        byte[] bytes= (byte[])lgIntObj.bits;
        for(int i=0; i<4; i++)
            bytes[i]= (byte) ((pos32Val>>>(8*i))&255);
        return lgIntObj; }

    SqueakObject stackNonInteger(int nDeep) {
        return checkNonSmallInt(vm.stackValue(nDeep)); }

    SqueakObject squeakBool(boolean bool) {
        return bool? vm.trueObj : vm.falseObj;}
    
    boolean primitiveAsFloat() {
	int intValue= stackInteger(0);
	if(!success) return false;
        vm.popNandPush(1,makeFloat(intValue));
        return true; }
                        
    boolean primitiveTruncate() {
	double floatVal= stackFloat(0);
        if( !(-1073741824.0 <= floatVal) && (floatVal <= 1073741823.0)) return false;
        vm.popNandPush(1,vm.smallFromInt((new Double(floatVal)).intValue())); //**must be a better way
        return true; }
            
    SqueakObject makeFloat(double value) {
        SqueakObject floatClass= (SqueakObject)vm.specialObjects[Squeak.splOb_ClassFloat];
        SqueakObject newFloat= vm.instantiateClass(floatClass,-1);
	newFloat.setFloatBits(value);
	return newFloat; }
	
    boolean primitiveMakePoint() {
        Object x= vm.stackValue(1);
        Object y= vm.stackValue(0);
        vm.popNandPush(2,makePointWithXandY(x,y)); return true;}

    SqueakObject makePointWithXandY(Object x, Object y) {
        SqueakObject pointClass= (SqueakObject)vm.specialObjects[Squeak.splOb_ClassPoint];
        SqueakObject newPoint= vm.instantiateClass(pointClass,0);
        newPoint.setPointer(Squeak.Point_x,x);
        newPoint.setPointer(Squeak.Point_y,y);
        return newPoint;}

    SqueakObject primitiveNewWithSize() {
        int size= stackPos32BitValue(0);
        if(!success) return vm.nilObj;
        return vm.instantiateClass(((SqueakObject)vm.stackValue(1)),size);}
    
    SqueakObject primitiveNewMethod() {
        Object headerInt= vm.top();
        int byteCount= stackInteger(1);
        int methodHeader= checkSmallInt(headerInt);
        if(!success) return vm.nilObj;
        int litCount= (methodHeader>>9)&0xFF;
        SqueakObject method= vm.instantiateClass(((SqueakObject)vm.stackValue(2)),byteCount);
        Object[] pointers= new Object[litCount+1];
        Arrays.fill(pointers,vm.nilObj);
        pointers[0]= headerInt;
        method.methodAddPointers(pointers);
        return method; }
    
    
    //String and Array Primitives
    SqueakObject makeStString(String javaString) {
        byte[] byteString= javaString.getBytes();
        SqueakObject stString= vm.instantiateClass((SqueakObject)vm.specialObjects[Squeak.splOb_ClassString],javaString.length());
        System.arraycopy(byteString,0,stString.bits,0,byteString.length);
        return stString;}

    Object primitiveSize() {//Returns size Integer (but may set success false)
        Object rcvr= vm.top();
        int size= indexableSize(rcvr);
        if(size == -1) success= false; //not indexable
        return pos32BitIntFor(size);}
    
    Object primitiveAt(boolean cameFromAtBytecode, boolean convertChars, boolean includeInstVars) {
        //Returns result of at: or sets success false
        SqueakObject array= stackNonInteger(1);
        int index= stackPos32BitValue(0); //note non-int returns zero
        if(!success) return array;
        AtCacheInfo info;
        if(cameFromAtBytecode) {// fast entry checks cache
            info= atCache[array.hashCode() & atCacheMask];
            if(info.array != array) {success= false; return array;} }
        else  {// slow entry installs in cache if appropriate
            if(array.format==6 && isA(array,Squeak.splOb_ClassFloat)) {
                // hack to make Float hash work
                long floatBits= Double.doubleToRawLongBits(array.getFloatBits());
                if(index==1) return pos32BitIntFor((int)(floatBits>>>32));
                if(index==2) return pos32BitIntFor((int)(floatBits&0xFFFFFFFF));
                success= false; return array;}
            info= makeCacheInfo(atCache, vm.specialSelectors[32], array, convertChars, includeInstVars); }
        if(index<1 || index>info.size) {success= false; return array;}
        if (includeInstVars)  //pointers...   instVarAt and objectAt
            return array.pointers[index-1];
        if (array.format<6)   //pointers...   normal at:
            return array.pointers[index-1+info.ivarOffset];
        if (array.format<8) { // words...
            int value= ((int[])array.bits)[index-1];
            return pos32BitIntFor(value); }
        if (array.format<12) { // bytes...
            int value= (((byte[])array.bits)[index-1]) & 0xFF;
            if(info.convertChars) return charFromInt(value);
                else return vm.smallFromInt(value); }
        // methods (format>=12) must simulate Squeak's method indexing
        int offset= array.pointersSize()*4;
        if(index-1-offset < 0) {success= false; return array;} //reading lits as bytes
        return vm.smallFromInt((((byte[])array.bits)[index-1-offset]) & 0xFF); }
    
    SqueakObject charFromInt(int ascii) {
        SqueakObject charTable= (SqueakObject)vm.specialObjects[Squeak.splOb_CharacterTable];
        return charTable.getPointerNI(ascii);}
        
    Object primitiveAtPut(boolean cameFromAtBytecode, boolean convertChars, boolean includeInstVars) {
        //Returns result of at:put: or sets success false
        SqueakObject array= stackNonInteger(2);
        int index= stackPos32BitValue(1); //note non-int returns zero
        if(!success) return array;
        AtCacheInfo info;
        if(cameFromAtBytecode) {// fast entry checks cache
            info= atPutCache[array.hashCode() & atCacheMask];
            if(info.array != array) {success= false; return array;} }
        else  {// slow entry installs in cache if appropriate
            info= makeCacheInfo(atPutCache, vm.specialSelectors[34], array, convertChars, includeInstVars); }
        if(index<1 || index>info.size) {success= false; return array;}
        Object objToPut= vm.stackValue(0);
        if (includeInstVars) { // pointers...   instVarAtPut and objectAtPut
                array.pointers[index-1]= objToPut; //eg, objectAt:
                return objToPut; }
        if (array.format<6) {  // pointers...   normal atPut
                array.pointers[index-1+info.ivarOffset]= objToPut;
                return objToPut; }
        int intToPut;
        if (array.format<8) {  // words...
            intToPut= stackPos32BitValue(0);
            if(!success) return objToPut;
            ((int[])array.bits)[index-1]= intToPut;
            return objToPut;}
        // bytes...
        if(info.convertChars) {
            // put a character...
            if(vm.isSmallInt(objToPut)) {success= false; return objToPut;}
            SqueakObject sqObjToPut= (SqueakObject)objToPut;
            if((sqObjToPut.sqClass != vm.specialObjects[Squeak.splOb_ClassCharacter]))
                {success= false; return objToPut;}
            Object asciiToPut= sqObjToPut.getPointer(0);
            if(!(vm.isSmallInt(asciiToPut))) {success= false; return objToPut;}
            intToPut= vm.intFromSmall((Integer)asciiToPut);}
        else { // put a byte...
            if(!(vm.isSmallInt(objToPut))) {success= false; return objToPut;}
            intToPut= vm.intFromSmall((Integer)objToPut);}
        if(intToPut<0 || intToPut>255) {success= false; return objToPut;}
        if (array.format<8) {  // bytes...
            ((byte[])array.bits)[index-1]= (byte)intToPut;
            return objToPut; }
        // methods (format>=12) must simulate Squeak's method indexing
        int offset= array.pointersSize()*4;
        if(index-1-offset < 0) {success= false; return array;} //writing lits as bytes
        ((byte[])array.bits)[index-1-offset]= (byte)intToPut;
        return objToPut; }

    
    int indexableSize(Object obj) {
        if(vm.isSmallInt(obj)) return -1; // -1 means not indexable
        SqueakObject sqObj= (SqueakObject) obj;
        short fmt= sqObj.format;
        if(fmt<2) return -1; //not indexable
        if(fmt==3 && vm.isContext(sqObj)) return sqObj.getPointerI(Squeak.Context_stackPointer).intValue();
        if(fmt<6) return sqObj.pointersSize() - sqObj.instSize(); // pointers
        if(fmt<12) return sqObj.bitsSize(); // words or bytes
        return sqObj.bitsSize() + (4*sqObj.pointersSize()); } // methods
    
    SqueakObject primitiveStringReplace() {
        SqueakObject dst= (SqueakObject)vm.stackValue(4);
	int dstPos= stackInteger(3)-1;
	int count= stackInteger(2) - dstPos;
//	if(count<=0) {success= false; return dst;} //fail for compat, later succeed
        SqueakObject src= (SqueakObject)vm.stackValue(1);
	int srcPos= stackInteger(0)-1;
        if(!success) return vm.nilObj; //some integer not right
        short srcFmt= src.format;
        short dstFmt= dst.format;
	if(dstFmt < 8)
            if(dstFmt != srcFmt) {success= false; return dst;} //incompatible formats
        else
            if((dstFmt&0xC) != (srcFmt&0xC)) {success= false; return dst;} //incompatible formats
        if(srcFmt<4) {//pointer type objects
            int totalLength= src.pointersSize();
            int srcInstSize= src.instSize();
            srcPos+= srcInstSize;
            if((srcPos < 0) || (srcPos + count) > totalLength)
                {success= false; return vm.nilObj;} //would go out of bounds
            totalLength= dst.pointersSize();
            int dstInstSize= dst.instSize();
            dstPos+= dstInstSize;
            if((dstPos < 0) || (dstPos + count) > totalLength)
                {success= false; return vm.nilObj;} //would go out of bounds
            System.arraycopy(src.pointers, srcPos, dst.pointers, dstPos, count);
            return dst;}
        else {//bits type objects
            int totalLength= src.bitsSize();
            if((srcPos < 0) || (srcPos + count) > totalLength)
                {success= false; return vm.nilObj;} //would go out of bounds
            totalLength= dst.bitsSize();
            if((dstPos < 0) || (dstPos + count) > totalLength)
                {success= false; return vm.nilObj;} //would go out of bounds
            System.arraycopy(src.bits, srcPos, dst.bits, dstPos, count);
            return dst;} }
        
    boolean primitiveNext() { //Not yet implemented...
	// PrimitiveNext should succeed only if the stream's array is in the atCache.
	// Otherwise failure will lead to proper message lookup of at: and
	// subsequent installation in the cache if appropriate."
        SqueakObject stream= stackNonInteger(0);
        if(!success) return false;
        Object[] streamBody= stream.pointers;
        if(streamBody == null || streamBody.length < (Squeak.Stream_limit+1))
            return false;
        Object array= streamBody[Squeak.Stream_array];
        if(vm.isSmallInt(array)) return false;
        int index= checkSmallInt(streamBody[Squeak.Stream_position]);
        int limit= checkSmallInt(streamBody[Squeak.Stream_limit]);
        int arraySize= indexableSize(array);
        if(index >= limit) return false;
//	(index < limit and: [(atCache at: atIx+AtCacheOop) = array])
//		ifFalse: [^ self primitiveFail].
//
//	"OK -- its not at end, and the array is in the cache"
//	index _ index + 1.
//	result _ self commonVariable: array at: index cacheIndex: atIx.
//	"Above may cause GC, so can't use stream, array etc. below it"
//	successFlag ifTrue:
//		[stream _ self stackTop.
//		self storeInteger: StreamIndexIndex ofObject: stream withValue: index.
//		^ self pop: 1 thenPush: result].
        return false; }
    
    SqueakObject primitiveBlockCopy() {
        Object rcvr= vm.stackValue(1);
        if(vm.isSmallInt(rcvr)) success= false;
        Object sqArgCount= vm.top();
        if(!(vm.isSmallInt(sqArgCount))) success= false;
        SqueakObject homeCtxt= (SqueakObject) rcvr;
        if(!vm.isContext(homeCtxt)) success= false;
        if(!success) return vm.nilObj;
        if(vm.isSmallInt(homeCtxt.getPointer(Squeak.Context_method)))
            // ctxt is itself a block; get the context for its enclosing method
            homeCtxt= homeCtxt.getPointerNI(Squeak.BlockContext_home);
        int blockSize= homeCtxt.pointersSize() - homeCtxt.instSize(); //can use a const for instSize
        SqueakObject newBlock= vm.instantiateClass(((SqueakObject)vm.specialObjects[Squeak.splOb_ClassBlockContext]),blockSize);
        Integer initialPC= vm.encodeSqueakPC(vm.pc+2,vm.method); //*** check this...
        newBlock.setPointer(Squeak.BlockContext_initialIP,initialPC);
        newBlock.setPointer(Squeak.Context_instructionPointer,initialPC);// claim not needed; value will set it
        newBlock.setPointer(Squeak.Context_stackPointer,vm.smallFromInt(0));
        newBlock.setPointer(Squeak.BlockContext_argumentCount,sqArgCount);
        newBlock.setPointer(Squeak.BlockContext_home,homeCtxt);
        newBlock.setPointer(Squeak.Context_sender,vm.nilObj);
        return newBlock;}
    
    boolean primitiveBlockValue(int argCount) {
        Object rcvr= vm.stackValue(argCount);
        if(!isA(rcvr,Squeak.splOb_ClassBlockContext)) return false;
        SqueakObject block= (SqueakObject) rcvr;
        Object blockArgCount= block.getPointer(Squeak.BlockContext_argumentCount);
        if(!vm.isSmallInt(blockArgCount)) return false;
        if((((Integer)blockArgCount).intValue()!= argCount)) return false;
        if(block.getPointer(Squeak.BlockContext_caller) != vm.nilObj) return false;
        System.arraycopy((Object)vm.activeContext.pointers,vm.sp-argCount+1,(Object)block.pointers,Squeak.Context_tempFrameStart,argCount);
        Integer initialIP= block.getPointerI(Squeak.BlockContext_initialIP);
        block.setPointer(Squeak.Context_instructionPointer,initialIP);
        block.setPointer(Squeak.Context_stackPointer,new Integer(argCount));
        block.setPointer(Squeak.BlockContext_caller,vm.activeContext);
        vm.popN(argCount+1);
        vm.newActiveContext(block);
        return true;}
    
    Object primitiveHash() {
        Object rcvr= vm.top();
        if(vm.isSmallInt(rcvr)) {success= false; return vm.nilObj;}
        return new Integer(((SqueakObject)rcvr).hash);}

    Object setLowSpaceThreshold() {
        int nBytes= stackInteger(0);
        if(success) vm.lowSpaceThreshold= nBytes;
        return vm.stackValue(1);}


    
    // Scheduler Primitives
    SqueakObject getScheduler() {
        SqueakObject assn= (SqueakObject)vm.specialObjects[Squeak.splOb_SchedulerAssociation];
        return assn.getPointerNI(Squeak.Assn_value);}

    boolean processResume() {
	SqueakObject process= (SqueakObject)vm.top();
	resume(process); return true;}
        
    boolean processSuspend() {
        SqueakObject activeProc= getScheduler().getPointerNI(Squeak.ProcSched_activeProcess);
	if(vm.top() != activeProc) return false;
	vm.popNandPush(1,vm.nilObj);
        transferTo(pickTopProcess());
        return true; }
                
    boolean isA(Object obj, int knownClass) {
        Object itsClass= vm.getClass(obj);
        return itsClass == vm.specialObjects[knownClass]; }

    boolean isKindOf(Object obj, int knownClass) {
        Object classOrSuper= vm.getClass(obj);
        Object theClass= vm.specialObjects[knownClass];
        while(classOrSuper != vm.nilObj) {
            if(classOrSuper == theClass) return true;
            classOrSuper= ((SqueakObject)classOrSuper).pointers[Squeak.Class_superclass]; }
        return false; }

    boolean semaphoreWait() {
	SqueakObject sema= (SqueakObject)vm.top();
        if(!isA(sema,Squeak.splOb_ClassSemaphore)) return false;
        int excessSignals= sema.getPointerI(Squeak.Semaphore_excessSignals).intValue();
        if (excessSignals > 0)
            sema.setPointer(Squeak.Semaphore_excessSignals,vm.smallFromInt(excessSignals-1));
        else {
            SqueakObject activeProc= getScheduler().getPointerNI(Squeak.ProcSched_activeProcess);
            linkProcessToList(activeProc, sema);
            transferTo(pickTopProcess());}
        return true; }
        
    boolean semaphoreSignal() {
	SqueakObject sema= (SqueakObject)vm.top();
        if(!isA(sema,Squeak.splOb_ClassSemaphore)) return false;
        synchronousSignal(sema);
        return true; }
        
    void synchronousSignal(SqueakObject sema) {
	if(isEmptyList(sema)) {
            //no process is waiting on this semaphore"
            int excessSignals= sema.getPointerI(Squeak.Semaphore_excessSignals).intValue();
            sema.setPointer(Squeak.Semaphore_excessSignals,vm.smallFromInt(excessSignals+1)); }
        else resume(removeFirstLinkOfList(sema));
        return; }

    void resume(SqueakObject newProc) {
	SqueakObject activeProc= getScheduler().getPointerNI(Squeak.ProcSched_activeProcess);
	int activePriority= activeProc.getPointerI(Squeak.Proc_priority).intValue();
        int newPriority= newProc.getPointerI(Squeak.Proc_priority).intValue();
	if(newPriority > activePriority) {
            putToSleep(activeProc);
            transferTo(newProc); }
        else {
            putToSleep(newProc); } }
        
    void putToSleep(SqueakObject aProcess) {
	//Save the given process on the scheduler process list for its priority.
	int priority= aProcess.getPointerI(Squeak.Proc_priority).intValue();
	SqueakObject processLists=  getScheduler().getPointerNI(Squeak.ProcSched_processLists);
	SqueakObject processList= processLists.getPointerNI(priority - 1);
        linkProcessToList(aProcess, processList);}

    void transferTo(SqueakObject newProc) { //Record a process to be awakened on the next interpreter cycle.
	SqueakObject sched=  getScheduler();
	SqueakObject oldProc= sched.getPointerNI(Squeak.ProcSched_activeProcess);
	sched.setPointer(Squeak.ProcSched_activeProcess,newProc);
        oldProc.setPointer(Squeak.Proc_suspendedContext,vm.activeContext);
//int prio= vm.intFromSmall((Integer)newProc.pointers[Squeak.Proc_priority]);
//System.err.println("Transfer to priority " + prio + " at byteCount " + vm.byteCount);
//if(prio==8)
//    vm.dumpStack();
        vm.newActiveContext(newProc.getPointerNI(Squeak.Proc_suspendedContext));
//System.err.println("new pc is " + vm.pc + "; method offset= " + ((vm.method.pointers.length+1)*4));
        newProc.setPointer(Squeak.Proc_suspendedContext,vm.nilObj);
        vm.reclaimableContextCount= 0; }
    
    SqueakObject pickTopProcess() { // aka wakeHighestPriority
	//Return the highest priority process that is ready to run.
	//Note: It is a fatal VM error if there is no runnable process.
	SqueakObject schedLists= getScheduler().getPointerNI(Squeak.ProcSched_processLists);
        int p= schedLists.pointersSize() - 1;  // index of last indexable field"
	p= p - 1;
	SqueakObject processList= schedLists.getPointerNI(p);
        while(isEmptyList(processList)) {
		p= p - 1;
		if(p < 0) return vm.nilObj; //self error: 'scheduler could not find a runnable process' ].
		processList= schedLists.getPointerNI(p);}
	return removeFirstLinkOfList(processList); }    
    
    void linkProcessToList(SqueakObject proc, SqueakObject aList) {
        // Add the given process to the given linked list and set the backpointer
	// of process to its new list."
	if(isEmptyList(aList))
            aList.setPointer(Squeak.LinkedList_firstLink,proc);
        else {
            SqueakObject lastLink= aList.getPointerNI(Squeak.LinkedList_lastLink);
            lastLink.setPointer(Squeak.Link_nextLink,proc);}
	aList.setPointer(Squeak.LinkedList_lastLink,proc);
        proc.setPointer(Squeak.Proc_myList,aList);}
        
    boolean isEmptyList(SqueakObject aLinkedList) {
        return aLinkedList.getPointerNI(Squeak.LinkedList_firstLink) == vm.nilObj;}
    
    SqueakObject removeFirstLinkOfList(SqueakObject aList) {
	//Remove the first process from the given linked list."
	SqueakObject first= aList.getPointerNI(Squeak.LinkedList_firstLink);
	SqueakObject last= aList.getPointerNI(Squeak.LinkedList_lastLink);
	if(first == last) {
            aList.setPointer(Squeak.LinkedList_firstLink,vm.nilObj);
            aList.setPointer(Squeak.LinkedList_lastLink,vm.nilObj);}
        else {
            SqueakObject next= first.getPointerNI(Squeak.Link_nextLink);
            aList.setPointer(Squeak.LinkedList_firstLink,next);}
	first.setPointer(Squeak.Link_nextLink,vm.nilObj);
        return first; }
            
    SqueakObject registerSemaphore(int specialObjSpec) {
        SqueakObject sema= (SqueakObject)vm.top();
        if(isA(sema,Squeak.splOb_ClassSemaphore))
            vm.specialObjects[specialObjSpec]= sema;
        else
            vm.specialObjects[specialObjSpec]= vm.nilObj;
        return (SqueakObject)vm.stackValue(1);}
    
    Object primitiveSignalAtMilliseconds() { //Delay signal:atMs:
	int msTime= stackInteger(0);
        Object sema= stackNonInteger(1);
        Object rcvr= stackNonInteger(2);
	if(!success) return vm.nilObj;
//System.err.println("Signal at " + msTime);
//vm.dumpStack();
	if(isA(sema,Squeak.splOb_ClassSemaphore)) {
            vm.specialObjects[Squeak.splOb_TheTimerSemaphore]= sema;
            vm.nextWakeupTick= msTime; }
        else {
            vm.specialObjects[Squeak.splOb_TheTimerSemaphore]= vm.nilObj;
            vm.nextWakeupTick= 0; }
	return rcvr; }  

        
//Other Primitives
    Integer millisecondClockValue() {
	//Return the value of the millisecond clock as an integer.
        //Note that the millisecond clock wraps around periodically.
        //The range is limited to SmallInteger maxVal / 2 to allow
        //delays of up to that length without overflowing a SmallInteger."
	return vm.smallFromInt((int) (System.currentTimeMillis() & (long)(vm.maxSmallInt>>1))); }

    boolean beDisplay(SqueakObject displayObj) {
        SqueakVM.FormCache disp= vm.newFormCache(displayObj);
        if(disp.squeakForm==null) return false;
        vm.specialObjects[Squeak.splOb_TheDisplay]= displayObj;
        displayBitmap= disp.bits;
        boolean remap= theDisplay != null;
        if (remap) {
                Dimension requestedExtent= new Dimension(disp.width, disp.height);
                if (!theDisplay.getExtent().equals(requestedExtent)) {
                        System.err.println("Squeak: changing screen size to " + disp.width + "@" + disp.height);
                        theDisplay.setExtent(requestedExtent); }
        } else {
                theDisplay= new Screen("Squeak", disp.width,disp.height,disp.depth,vm);
                theDisplay.getFrame().addWindowListener(new WindowAdapter() {
                        public void windowClosing(WindowEvent evt) {
                                // TODO ask before shutdown
                                // FIXME at least lock out quitting until concurrent image save has finished
                                   //exit(1);
                        } });
                }
        displayBitmapInBytes= new byte[displayBitmap.length*4];
        copyBitmapToByteArray(displayBitmap,displayBitmapInBytes,
                        new Rectangle(0,0,disp.width,disp.height),disp.pitch,disp.depth);
        theDisplay.setBits(displayBitmapInBytes, disp.depth);
        if (!remap) theDisplay.open();
	return true; }

    boolean beCursor(int argCount) {
        // For now we ignore the white outline form (maskObj)
        if(theDisplay == null) return true;
        SqueakObject cursorObj, maskObj;
        if(argCount==0) {
            cursorObj= stackNonInteger(0);
            maskObj= vm.nilObj; }
        else {
            cursorObj= stackNonInteger(1);
            maskObj= stackNonInteger(0); }
        SqueakVM.FormCache cursorForm= vm.newFormCache(cursorObj);
        if(!success || cursorForm.squeakForm==null) return false;
        //Following code for offset is not yet used...
        SqueakObject offsetObj= checkNonSmallInt(cursorObj.getPointer(4));
        if ( !isA(offsetObj,Squeak.splOb_ClassPoint)) return false;
	int offsetX = checkSmallInt(offsetObj.pointers[0]);
	int offsetY = checkSmallInt(offsetObj.pointers[1]);
        if (! success) return false;
        //Current cursor code in Screen expects cursor and mask to be packed in cursorBytes
        //For now we make them be equal copies of incoming 16x16 cursor
        int cursorBitsSize= cursorForm.bits.length;
        byte[] cursorBytes= new byte[8*cursorBitsSize];
	copyBitmapToByteArray(cursorForm.bits,cursorBytes,
                new Rectangle(0,0,cursorForm.width,cursorForm.height),
                cursorForm.pitch,cursorForm.depth);
        for(int i=0; i<(cursorBitsSize*4); i++)
            cursorBytes[i+(cursorBitsSize*4)]= cursorBytes[i];
        theDisplay.setCursor(cursorBytes,BWMask);
        return true; }
    
    boolean primitiveYield(int numArgs) { // halts execution until EHT callbacks notify us
        long millis= 100;
        if(numArgs > 1) return false;
        if(numArgs > 0) { // But, for now, wait time is ignored...
                int micros= stackInteger(0);
                if (!success) return false;
                vm.pop();
                millis= micros/1000; }
        // try { synchronized (vm) { vm.wait(); }
        //         } catch (InterruptedException e) { }
        // TODO how to handle third-party interruptions?
        try {
            synchronized(vm) {
                while(!vm.screenEvent) vm.wait(millis);
                }
            } catch(InterruptedException e) { }
        return true; }

    boolean primitiveCopyBits(SqueakObject rcvr, int argCount) { // no rcvr class check, to allow unknown subclasses (e.g. under Turtle)
        if(!bitbltTable.loadBitBlt(rcvr, argCount, false, (SqueakObject)vm.specialObjects[Squeak.splOb_TheDisplay])) return false;
	Rectangle affectedArea= bitbltTable.copyBits();
	if (affectedArea != null && theDisplay!=null) {
            copyBitmapToByteArray(displayBitmap,displayBitmapInBytes,affectedArea,
                                bitbltTable.dest.pitch,bitbltTable.dest.depth);
            theDisplay.redisplay(false, affectedArea); }
	if (bitbltTable.combinationRule == 22 || bitbltTable.combinationRule == 32)
		vm.popNandPush(2,vm.smallFromInt(bitbltTable.bitCount));
	return true; }
    
    void copyBitmapToByteArray(int[] words, byte[] bytes,Rectangle rect, int raster, int depth) {
        //Copy our 32-bit words into a byte array  until we find out
        // how to make AWT happy with int buffers
        int word;
        int ix1= rect.x/depth/32;
        int ix2= (rect.x+rect.width-1)/depth/32 + 1;
        for (int y=rect.y; y<rect.y+rect.height; y++) {
            int iy= y*raster;
            for(int ix=ix1; ix<ix2; ix++) {
                word= (words[iy+ix])^BWMask;
                for(int j=0; j<4; j++)
                    bytes[((iy+ix)*4)+j]= (byte)((word>>>((3-j)*8))&255); } }

//        int word;
//        for(int i=0; i<words.length; i++) {
//            word= ~(words[i]);
//            for(int j=0; j<4; j++)
//                bytes[(i*4)+j]= (byte)((word>>>((3-j)*8))&255); }
}
    
    SqueakObject primitiveMousePoint() {
        SqueakObject pointClass= (SqueakObject)vm.specialObjects[Squeak.splOb_ClassPoint];
        SqueakObject newPoint= vm.instantiateClass(pointClass,0);
        Point lastMouse= theDisplay.getLastMousePoint();
        newPoint.setPointer(Squeak.Point_x,vm.smallFromInt(lastMouse.x));
        newPoint.setPointer(Squeak.Point_y,vm.smallFromInt(lastMouse.y));
        return newPoint; }

    Integer primitiveMouseButtons() {
	return vm.smallFromInt(theDisplay.getLastMouseButtonStatus()); }

    Object primitiveKbdNext() {
	return vm.smallFromInt(theDisplay.keyboardNext()); }

    Object primitiveKbdPeek() {
	if(theDisplay==null) return (Object)vm.nilObj;
        int peeked= theDisplay.keyboardPeek();
	return peeked==0? (Object)vm.nilObj : vm.smallFromInt(peeked); }
    
    SqueakObject primitiveArrayBecome(boolean doBothWays) {
	// Should flush method cache
	SqueakObject rcvr= stackNonInteger(1);
        SqueakObject arg= stackNonInteger(0);
	if(!success) return rcvr;
        success= image.bulkBecome(rcvr.pointers, arg.pointers, doBothWays);
        return rcvr; }
    
    SqueakObject primitiveSomeObject() {
        return image.nextInstance(0, null); }
    
    SqueakObject primitiveSomeInstance(SqueakObject sqClass) {
        return image.nextInstance(0, sqClass); }
    
    Object primitiveNextObject(SqueakObject priorObject) {
        SqueakObject nextObject= image.nextInstance(image.otIndexOfObject(priorObject)+1, null);
        if(nextObject==vm.nilObj) return vm.smallFromInt(0);
        return nextObject; }
    
    SqueakObject primitiveNextInstance(SqueakObject priorInstance) {
        SqueakObject sqClass= (SqueakObject)priorInstance.sqClass;
        return image.nextInstance(image.otIndexOfObject(priorInstance)+1, sqClass); }
    
    boolean relinquishProcessor() {
        int periodInMicroseconds= stackInteger(0); //NOTE: argument is *ignored*
        vm.pop();
//        Thread.currentThread().yield();
//        try {vm.wait(50L); } // 50 millisecond pause
//            catch (InterruptedException e) {}
        return true; }
    
    Object primSeconds() {
        int secs= (int)(System.currentTimeMillis()/1000); //milliseconds -> seconds
        secs+= ((69*365+17)*24*3600); //Adjust from 1901 to 1970
        return pos32BitIntFor(secs); }
    
}