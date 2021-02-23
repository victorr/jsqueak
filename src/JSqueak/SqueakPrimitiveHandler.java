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
import java.util.Arrays;

/**
 * @author Daniel Ingalls
 *
 * Implements the indexed primitives for the Squeak VM.
 */
class SqueakPrimitiveHandler 
{
    private final PrimitiveFailedException PrimitiveFailed = new PrimitiveFailedException();
    
    private final SqueakVM vm;
    private final SqueakImage image;
    private final BitBlt bitbltTable;

    private final FileSystemPrimitives fileSystemPrimitives = new FileSystemPrimitives( this );
    
    private Screen theDisplay;
    private int[] displayBitmap;
    private int displayRaster;
    private byte[] displayBitmapInBytes;
    private int BWMask= 0;
    
    
    // Its purpose of the at-cache is to allow fast (bytecode) access to at/atput code
    // without having to check whether this object has overridden at, etc.
    private final int atCacheSize= 32; // must be power of 2
    private final int atCacheMask= atCacheSize-1; //...so this is a mask
    
    private AtCacheInfo[] atCache;
    private AtCacheInfo[] atPutCache;
    private AtCacheInfo nonCachedInfo;
    
    SqueakPrimitiveHandler(SqueakVM theVM) 
    {
        vm= theVM;
        image= vm.image;
        bitbltTable= new BitBlt(vm);
        initAtCache(); 
    }
    
    /**
     * A singleton instance of this class should be thrown to signal that a 
     * primitive has failed.
     */
    private static class PrimitiveFailedException extends RuntimeException 
    {
    }

    private static class AtCacheInfo 
    {
        SqueakObject array;
        int size;
        int ivarOffset;
        boolean convertChars; 
    }
    
    private void initAtCache() 
    {
        atCache= new AtCacheInfo[atCacheSize];
        atPutCache= new AtCacheInfo[atCacheSize];
        nonCachedInfo= new AtCacheInfo();
        for(int i= 0; i<atCacheSize; i++)
        {
            atCache[i]= new AtCacheInfo();
            atPutCache[i]= new AtCacheInfo();
        } 
    }
    
    /**
     * Clear at-cache pointers (prior to GC). 
     */
    void clearAtCache() 
    {
        for(int i= 0; i<atCacheSize; i++) 
        {
            atCache[i].array= null;
            atPutCache[i].array= null;
        }
    }
    
    private AtCacheInfo makeCacheInfo(AtCacheInfo[] atOrPutCache, Object atOrPutSelector, SqueakObject array, boolean convertChars, boolean includeInstVars) 
    {
        //Make up an info object and store it in the atCache or the atPutCache.
        //If it's not cacheable (not a non-super send of at: or at:put:)
        //then return the info in nonCachedInfo.
        //Note that info for objectAt (includeInstVars) will have
        //a zero ivarOffset, and a size that includes the extra instVars
        AtCacheInfo info;
        boolean cacheable= (vm.verifyAtSelector == atOrPutSelector) //is at or atPut
            && (vm.verifyAtClass == array.getSqClass())         //not a super send
            && (array.format==3 && vm.isContext(array));        //not a context (size can change)
        if (cacheable) 
            info= atOrPutCache[array.hashCode() & atCacheMask];
        else
            info= nonCachedInfo;
        info.array= array;
        info.convertChars= convertChars; 
        if (includeInstVars) 
        {
            info.size= Math.max(0,indexableSize(array)) + array.instSize();
            info.ivarOffset= 0; 
        }
        else 
        {
            info.size= indexableSize(array);
            info.ivarOffset= (array.format<6) ? array.instSize() : 0; 
        }
        return info; 
    }
    
    // Quick Sends from inner Interpreter
    boolean quickSendOther(Object rcvr, int lobits) 
    {
        // QuickSendOther returns true if it succeeds
        try
        {
            switch (lobits) 
            {
                case 0x0: popNandPush(2,primitiveAt(true,true,false)); // at:
                          break;
                case 0x1: popNandPush(3,primitiveAtPut(true,true,false)); // at:put:
                          break;
                case 0x2: popNandPush(1,primitiveSize()); // size
                          break;
                case 0x3: return false; // next
                case 0x4: return false; // nextPut
                case 0x5: return false; // atEnd
                case 0x6: return pop2andDoBool(primitiveEq(vm.stackValue(1),vm.stackValue(0))); // ==
                case 0x7: popNandPush(1,vm.getClass(vm.top())); // class
                          break;
                case 0x8: popNandPush(2,primitiveBlockCopy()); // blockCopy:
                          break;
                case 0x9: primitiveBlockValue(0); // value
                          break;
                case 0xa: primitiveBlockValue(1); // value:
                          break;
                case 0xb: return false; // do:
                case 0xc: return false; // new
                case 0xd: return false; // new:
                case 0xe: return false; // x
                case 0xf: return false; // y
                default: return false; 
            }
            return true;
        }
        catch ( PrimitiveFailedException exception )
        {
            return false;
        }
    }

    private static boolean primitiveEq(Object arg1, Object arg2) 
    {
        // == must work for uninterned small ints
        if (SqueakVM.isSmallInt(arg1) && SqueakVM.isSmallInt(arg2))
            return ((Integer)arg1).intValue() == ((Integer)arg2).intValue();
        return arg1==arg2; 
    }
    
    private Object primitiveBitAnd() 
    {
        int rcvr = stackPos32BitValue(1);
        int arg  = stackPos32BitValue(0);

        return pos32BitIntFor(rcvr & arg); 
    }
    
    private Object primitiveBitOr() 
    {
        int rcvr= stackPos32BitValue(1);
        int arg= stackPos32BitValue(0);

        return pos32BitIntFor(rcvr | arg); 
    }
    
    private Object primitiveBitXor() 
    {
        int rcvr= stackPos32BitValue(1);
        int arg= stackPos32BitValue(0);
        
        return pos32BitIntFor(rcvr ^ arg); 
    }
    
    private Object primitiveBitShift() 
    {
        int rcvr= stackPos32BitValue(1);
        int arg= stackInteger(0);

        return pos32BitIntFor(SqueakVM.safeShift(rcvr,arg)); 
    }
    
    private int doQuo(int rcvr, int arg) 
    {
        if (arg == 0) 
            throw PrimitiveFailed;
//            success= false; return 0;  // FIXME: Why doesn't doQuo() return nonSmallInt
        
        if (rcvr > 0) 
        {
            if (arg > 0) 
                return rcvr / arg;
            else 
                return 0 - (rcvr / (0 - arg)); 
        }
        else 
        {
            if (arg > 0)
                return 0 - ((0 - rcvr) / arg);
            else 
                return (0 - rcvr) / (0 - arg); 
        }
    }
    
    boolean doPrimitive(int index, int argCount) 
    {
        try
        {
            switch (index) 
            {
                // 0..127
                case 1: popNandPushInt(2,stackInteger(1)+stackInteger(0));  // Integer.add
                        break;
                case 2: popNandPushInt(2,stackInteger(1)-stackInteger(0));  // Integer.subtract
                        break;
                case 3: return pop2andDoBool(stackInteger(1)<stackInteger(0));  // Integer.less
                case 4: return pop2andDoBool(stackInteger(1)>stackInteger(0));  // Integer.greater
                case 5: return pop2andDoBool(stackInteger(1)<=stackInteger(0));  // Integer.leq
                case 6: return pop2andDoBool(stackInteger(1)>=stackInteger(0));  // Integer.geq
                case 7: return pop2andDoBool(stackInteger(1)==stackInteger(0));  // Integer.equal
                case 8: return pop2andDoBool(stackInteger(1)!=stackInteger(0));  // Integer.notequal
                case 9: popNandPushInt(2,SqueakVM.safeMultiply(stackInteger(1),stackInteger(0)));  // Integer.multiply *
                        break;
                case 10: popNandPushInt(2,SqueakVM.quickDivide(stackInteger(1),stackInteger(0)));  // Integer.divide /  (fails unless exact exact)
                         break;
                case 11: return false; //popNandPushIntIfOK(2,doMod(stackInteger(1),stackInteger(0)));  // Integer.mod \\
                case 12: popNandPushInt(2,SqueakVM.div(stackInteger(1),stackInteger(0)));  // Integer.div //
                         break;
                case 13: popNandPushInt(2,doQuo(stackInteger(1),stackInteger(0)));  // Integer.quo
                         break;
                case 14: popNandPush(2,primitiveBitAnd());  // SmallInt.bitAnd
                         break;
                case 15: popNandPush(2,primitiveBitOr());  // SmallInt.bitOr
                         break;
                case 16: popNandPush(2,primitiveBitXor());  // SmallInt.bitXor
                         break;
                case 17: popNandPush(2,primitiveBitShift());  // SmallInt.bitShift
                         break;
                case 18: return primitiveMakePoint();
                case 40: popNandPush(1, primitiveAsFloat() );
                         break;
                case 41: popNandPushFloat(2,stackFloat(1)+stackFloat(0));  // Float +        // +
                         break;
                case 42: popNandPushFloat(2,stackFloat(1)-stackFloat(0));  // Float -
                         break;
                case 43: return pop2andDoBool(stackFloat(1)<stackFloat(0));  // Float <
                case 44: return pop2andDoBool(stackFloat(1)>stackFloat(0));  // Float >
                case 45: return pop2andDoBool(stackFloat(1)<=stackFloat(0));  // Float <=
                case 46: return pop2andDoBool(stackFloat(1)>=stackFloat(0));  // Float >=
                case 47: return pop2andDoBool(stackFloat(1)==stackFloat(0));  // Float =
                case 48: return pop2andDoBool(stackFloat(1)!=stackFloat(0));  // Float !=
                case 49: popNandPushFloat(2,stackFloat(1)*stackFloat(0));  // Float.mul
                         break;
                case 50: popNandPushFloat(2,safeFDiv(stackFloat(1),stackFloat(0)));  // Float.div
                         break;
                case 51: popNandPush( 1, primitiveTruncate() );
                         break;
                case 58: popNandPushFloat(1,StrictMath.log(stackFloat(0)));  // Float.ln
                         break;
                case 60: popNandPush(2,primitiveAt(false,false,false)); // basicAt:
                         break;
                case 61: popNandPush(3,primitiveAtPut(false,false,false)); // basicAt:put:
                         break;
                case 62: popNandPush(1,primitiveSize()); // size
                         break;
                case 63: popNandPush(2,primitiveAt(false,true,false)); // basicAt:
                         break;
                case 64: popNandPush(3,primitiveAtPut(false,true,false)); // basicAt:put:
                         break;
                case 68: popNandPush(2,primitiveAt(false,false,true)); // Method.objectAt:
                         break;
                case 69: popNandPush(3,primitiveAtPut(false,false,true)); // Method.objectAt:put:
                         break;
                case 70: popNandPush(1,vm.instantiateClass( stackNonInteger( 0 ), 0 ) ); // Class.new
                         break;
                case 71: popNandPush(2,primitiveNewWithSize()); // Class.new
                         break;
                case 72: popNandPush(2,primitiveArrayBecome(false));
                         break;
                case 73: popNandPush(2,primitiveAt(false,false,true)); // instVarAt:
                         break;
                case 74: popNandPush(3,primitiveAtPut(false,false,true)); // instVarAt:put:
                         break;
                case 75: popNandPush(1,primitiveHash()); // Class.identityHash
                         break;
                case 77: popNandPush(1,primitiveSomeInstance(stackNonInteger(0))); // Class.someInstance
                         break;
                case 78: popNandPush(1,primitiveNextInstance(stackNonInteger(0))); // Class.someInstance
                         break;
                case 79: popNandPush(3,primitiveNewMethod()); // Compiledmethod.new
                         break;
                case 80: popNandPush(2,primitiveBlockCopy()); // Context.blockCopy:
                         break;
                case 81: primitiveBlockValue(argCount); // BlockContext.value
                         break;
                case 83: return vm.primitivePerform(argCount); // rcvr.perform:(with:)*
                case 84: return vm.primitivePerformWithArgs(vm.getClass(vm.stackValue(2))); // rcvr.perform:withArguments:
                case 85: semaphoreSignal(); // Semaphore.wait
                         break;
                case 86: semaphoreWait(); // Semaphore.wait
                         break;
                case 87: processResume(); // Process.resume
                         break;
                case 88: processSuspend(); // Process.suspend
                         break;
                case 89: return vm.clearMethodCache();  // selective
                case 90: popNandPush(1,primitiveMousePoint()); // mousePoint
                         break;
                case 96: if (argCount==0) 
                             primitiveCopyBits((SqueakObject)vm.top(),0);
                         else 
                             primitiveCopyBits((SqueakObject)vm.stackValue(1),1);
                         break;
                case 97: primitiveSnapshot();
                         break;
                case 100: return vm.primitivePerformInSuperclass((SqueakObject)vm.top()); // rcvr.perform:withArguments:InSuperclass
                case 101: beCursor(argCount); // Cursor.beCursor
                          break;
                case 102: beDisplay((SqueakObject)vm.top()); // DisplayScreen.beDisplay
                          break;
                case 105: popNandPush(5,primitiveStringReplace()); // string and array replace
                          break;
                case 106: popNandPush(1,makePointWithXandY(SqueakVM.smallFromInt(640),SqueakVM.smallFromInt(480))); // actualScreenSize // FIXME: Use real size
                          break;
                case 107: popNandPush(1,primitiveMouseButtons()); // Sensor mouseButtons
                          break;
                case 108: popNandPush(1,primitiveKbdNext()); // Sensor kbdNext
                          break;
                case 109: popNandPush(1,primitiveKbdPeek()); // Sensor kbdPeek
                          break;
                case 110: popNandPush(2,(vm.stackValue(1) == vm.stackValue(0))? vm.trueObj : vm.falseObj); // ==
                          break;
                case 112: popNandPush(1,SqueakVM.smallFromInt(image.spaceLeft())); // bytesLeft
                          break;
                case 113: System.exit(0);
                case 116: return vm.flushMethodCacheForMethod((SqueakObject)vm.top());
                case 119: return vm.flushMethodCacheForSelector((SqueakObject)vm.top());
                case 121: popNandPush(1, primitiveImageFileName( argCount  ) );
                          break;
                case 122: BWMask= ~BWMask;
                          break;
                case 124: popNandPush(2,registerSemaphore(Squeak.splOb_TheLowSpaceSemaphore));
                          break;
                case 125: popNandPush(2,setLowSpaceThreshold());
                          break;
                case 128: popNandPush(2,primitiveArrayBecome(true));
                          break;
                case 129: popNandPush(1,image.specialObjectsArray);
                          break;
                case 130: popNandPush(1,SqueakVM.smallFromInt(image.fullGC())); // GC
                          break;
                case 131: popNandPush(1,SqueakVM.smallFromInt(image.partialGC())); // GCmost
                          break;
                case 134: popNandPush(2,registerSemaphore(Squeak.splOb_TheInterruptSemaphore));
                          break;
                case 135: popNandPush(1,millisecondClockValue());
                          break;
                case 136: popNandPush(3,primitiveSignalAtMilliseconds()); //Delay signal:atMs:());
                          break;
                case 137: popNandPush(1,primSeconds()); //Seconds since Jan 1, 1901
                          break;
                case 138: popNandPush(1,primitiveSomeObject()); // Class.someInstance
                          break;
                case 139: popNandPush(1,primitiveNextObject(stackNonInteger(0))); // Class.someInstance
                          break;
                case 142: popNandPush(1, primitiveVmPath() );
                          break;
                case 148: popNandPush(1,((SqueakObject)vm.top()).cloneIn(image)); //imageName
                          break;
                case 149: popNandPush(2,vm.nilObj); //getAttribute
                          break;
                          
                // File System primitives
                case 150: popNandPush( 2, fileSystemPrimitives.fileAtEnd( argCount ) );
                          break;
                case 151: popNandPush( 2, fileSystemPrimitives.fileClose( argCount ) );
                          break;
                case 152: popNandPush( 2, fileSystemPrimitives.getPosition( argCount ) );
                          break;
                case 153: popNandPush( 3, fileSystemPrimitives.openWritable( argCount ) );
                          break;
                case 154: popNandPush( 5, fileSystemPrimitives.readIntoStartingAtCount( argCount ) );
                          break;
                case 155: popNandPush( 3, fileSystemPrimitives.fileSetPosition( argCount ) );
                          break;
                case 156: popNandPush( 2, fileSystemPrimitives.fileDelete( argCount ) );
                          break;
                case 157: popNandPush( 2, fileSystemPrimitives.fileSize( argCount ) );
                          break;
                case 158: popNandPush( 5, fileSystemPrimitives.fileWrite( argCount ) );
                          break;
                case 159: popNandPush( 3, fileSystemPrimitives.fileRename( argCount ) );
                          break;
                case 160: popNandPush( 2, fileSystemPrimitives.directoryCreate( argCount ) );
                          break;
                case 161: popNandPush( 1, fileSystemPrimitives.directoryDelimitor()); //path delimiter
                          break;
                case 162: popNandPush( 3, fileSystemPrimitives.lookupEntryInIndex( argCount ) ); //path delimiter
                          break;
                          
                case 230: primitiveYield(argCount); //yield for 10ms
                          break;
                default: return false; 
            }
            return true;
        }
        catch ( PrimitiveFailedException exception )
        {
            return false;
        }
    }

    /**
     * snapshotPrimitive
     *    "Primitive. Write the current state of the object memory on a file in the
     *    same format as the Smalltalk-80 release. The file can later be resumed,
     *    returning you to this exact state. Return normally after writing the file.
     *    Essential. See Object documentation whatIsAPrimitive."
     *    
     *    <primitive: 97>
     *     ^nil "indicates error writing image file"
     */
    private void primitiveSnapshot()
    {
        System.out.println( "Saving the image" );
        try 
        {
            vm.image.save( new File( "/tmp/image.gz" ) );
        }
        catch ( IOException e ) 
        {
            e.printStackTrace();
            throw PrimitiveFailed;
        }
    }
    
    /**
     * Primitive 121
     * "When called with a single string argument, record the string
     * as the current image file name. When called with zero
     * arguments, return a string containing the current image file
     * name."
     */
    private Object primitiveImageFileName( int argCount ) 
    {
        if ( argCount == 0 )
            return makeStString( vm.image.imageFile().getAbsolutePath() );
        
        if ( argCount == 1 )
            new Exception( "Cannot set the image name yet, argument is '" + stackNonInteger( 0 ) + "'" ).printStackTrace();

        throw PrimitiveFailed;
    }

    /**
     * SystemDictionary>>vmPath.
     * Primitive 142.
     * 
     * primVmPath
     *   "Answer the path for the directory containing the Smalltalk virtual machine. 
     *   Return the empty string if this primitive is not implemented."
     *        "Smalltalk vmPath"
     *
     *   <primitive: 142>
     *   ^ ''
     */
    private SqueakObject primitiveVmPath()
    {
        return makeStString( System.getProperty( "user.dir" ) );
    }

    private boolean pop2andDoBool(boolean bool) 
    {
        vm.success= true; // FIXME: Why have a side effect here? 
        return vm.pushBoolAndPeek(bool); 
    }
    
    
    private void popNandPush(int nToPop, Object returnValue) 
    {
        if ( returnValue == null )
            new Exception( "NULL in popNandPush()" ).printStackTrace(); // FIXME: Did I break this by not checking for a null return value?
        
        vm.popNandPush(nToPop,returnValue);
    }
    
    private void popNandPushInt(int nToPop, int returnValue) 
    {
        Integer value = SqueakVM.smallFromInt(returnValue);
        if ( value == null )
            throw PrimitiveFailed;
        
        popNandPush(nToPop, value); 
    }
    
    private void popNandPushFloat(int nToPop, double returnValue) 
    {
        
        popNandPush( nToPop, makeFloat( returnValue ) ); 
    }
    
    int stackInteger(int nDeep) 
    {
        return checkSmallInt(vm.stackValue(nDeep)); 
    }
    
    /**
     * If maybeSmall is a small integer, return its value, fail otherwise.
     */
    private int checkSmallInt(Object maybeSmall)
    {
        if (SqueakVM.isSmallInt(maybeSmall))
			return SqueakVM.intFromSmall(((Integer)maybeSmall));
        
        throw PrimitiveFailed;
    }
    
    private double stackFloat(int nDeep) 
    {
        return checkFloat(vm.stackValue(nDeep)); 
    }
    
    /**
     * If maybeFloat is a Squeak Float return its value, fail otherwise
     */
    private double checkFloat(Object maybeFloat) 
    {
        if (vm.getClass(maybeFloat)==vm.specialObjects[Squeak.splOb_ClassFloat])
            return ((SqueakObject)maybeFloat).getFloatBits();
        
        throw PrimitiveFailed;
    }
    
    private double safeFDiv(double dividend, double divisor) 
    {
        if (divisor == 0.0d)
            throw PrimitiveFailed;

        return dividend / divisor; 
    }
    
    /**
     * Fail if maybeSmall is not a SmallInteger
     * @param maybeSmall
     * @return
     */
    private SqueakObject checkNonSmallInt(Object maybeSmall)  // returns a SqObj and sets success 
    {
        if (SqueakVM.isSmallInt(maybeSmall))
            throw PrimitiveFailed;
        
        return (SqueakObject) maybeSmall; 
    }
    
    int stackPos32BitValue(int nDeep) 
    {
        Object stackVal= vm.stackValue(nDeep);
        if (SqueakVM.isSmallInt(stackVal)) 
        {
            int value= SqueakVM.intFromSmall(((Integer) stackVal));
            if (value >= 0)
                return value;
            
            throw PrimitiveFailed;
        }
        
        if (!isA(stackVal,Squeak.splOb_ClassLargePositiveInteger))
            throw PrimitiveFailed;

        byte[] bytes= (byte[])((SqueakObject)stackVal).bits;
        int value= 0;
        for(int i=0; i<4; i++)
            value= value + ((bytes[i]&255)<<(8*i));
        return value; 
    }

    Object pos32BitIntFor( long pos32Val )
    {
        if ( pos32Val < Integer.MIN_VALUE ||
             pos32Val > Integer.MAX_VALUE )
        {
            new Exception( "long to int overflow" ).printStackTrace();
            throw PrimitiveFailed;
        }
        
        return pos32BitIntFor( (int) pos32Val ); 
    }
    
    Object pos32BitIntFor(int pos32Val) 
    {
        // Return the 32-bit quantity as a positive 32-bit integer
        if (pos32Val >= 0) 
        {
            Object smallInt= SqueakVM.smallFromInt(pos32Val);
            if (smallInt != null) 
                return smallInt; 
        }
        SqueakObject lgIntClass= (SqueakObject)vm.specialObjects[Squeak.splOb_ClassLargePositiveInteger];
        SqueakObject lgIntObj= vm.instantiateClass(lgIntClass,4);
        byte[] bytes= (byte[])lgIntObj.bits;
        for(int i=0; i<4; i++)
            bytes[i]= (byte) ((pos32Val>>>(8*i))&255);
        return lgIntObj; 
    }
    
    SqueakObject stackNonInteger(int nDeep) 
    {
        return checkNonSmallInt(vm.stackValue(nDeep)); 
    }

    SqueakObject squeakArray( Object[] javaArray )
    {
        SqueakObject array = vm.instantiateClass( Squeak.splOb_ClassArray, javaArray.length );
        for ( int index = 0; index < javaArray.length; index++ )
            array.setPointer( index, javaArray[index] );

        return array;
    }

    Object squeakSeconds( long millis )
    {
        int secs = (int) ( millis / 1000 ); //milliseconds -> seconds
        secs += ( 69 * 365 + 17 ) * 24 * 3600; //Adjust from 1901 to 1970
        
        return pos32BitIntFor(secs);
    }


    SqueakObject squeakNil()
    {
        return vm.nilObj;
    }
    
    SqueakObject squeakBool( boolean bool ) 
    {
        return bool ? vm.trueObj : vm.falseObj; 
    }
    
    /**
     * Note: this method does not check to see that the passed
     *       object is an instance of Boolean.
     *       
     * @return true iff object is the special Squeak true object
     */
    boolean javaBool( SqueakObject object )
    {
        return object == vm.trueObj; 
    }
    
    private SqueakObject primitiveAsFloat() 
    {
        int intValue = stackInteger(0);

        return makeFloat(intValue);
    }

    private Object primitiveTruncate() 
    {
        double floatVal = stackFloat( 0 );
        if ( !(-1073741824.0 <= floatVal) && (floatVal <= 1073741823.0)) 
            throw PrimitiveFailed;
        
        return SqueakVM.smallFromInt( (new Double(floatVal)).intValue()); //**must be a better way  Probably Math.round()?
    }
            
    private SqueakObject makeFloat(double value) 
    {
        SqueakObject floatClass= (SqueakObject)vm.specialObjects[Squeak.splOb_ClassFloat];
        SqueakObject newFloat= vm.instantiateClass(floatClass,-1);
        newFloat.setFloatBits(value);
        return newFloat; 
    }
    
    boolean primitiveMakePoint() 
    {
        Object x= vm.stackValue(1);
        Object y= vm.stackValue(0);
        vm.popNandPush(2,makePointWithXandY(x,y)); 
        return true; 
    }
    
    private SqueakObject makePointWithXandY(Object x, Object y) 
    {
        SqueakObject pointClass= (SqueakObject)vm.specialObjects[Squeak.splOb_ClassPoint];
        SqueakObject newPoint= vm.instantiateClass(pointClass,0);
        newPoint.setPointer(Squeak.Point_x,x);
        newPoint.setPointer(Squeak.Point_y,y);
        return newPoint; 
    }
    
    private SqueakObject primitiveNewWithSize() 
    {
        int size= stackPos32BitValue(0);
        
        return vm.instantiateClass(((SqueakObject)vm.stackValue(1)),size); 
    }
    
    private SqueakObject primitiveNewMethod() 
    {
        Object headerInt= vm.top();
        int byteCount= stackInteger(1);
        int methodHeader= checkSmallInt(headerInt);
        int litCount= (methodHeader>>9)&0xFF;
        SqueakObject method= vm.instantiateClass(((SqueakObject)vm.stackValue(2)),byteCount);
        Object[] pointers= new Object[litCount+1];
        Arrays.fill(pointers,vm.nilObj);
        pointers[0]= headerInt;
        method.methodAddPointers(pointers);
        return method; 
    }
    
    
    //String and Array Primitives
    // FIXME: makeStString() but squeakBool() ? Pick one!
    SqueakObject makeStString(String javaString) 
    {
        byte[] byteString= javaString.getBytes();
        SqueakObject stString= vm.instantiateClass((SqueakObject)vm.specialObjects[Squeak.splOb_ClassString],javaString.length());
        System.arraycopy(byteString,0,stString.bits,0,byteString.length);
        return stString; 
    }

    /**
     * Returns size Integer (but may set success false) 
     */
    private Object primitiveSize() 
    {
        Object rcvr = vm.top();
        int size = indexableSize(rcvr);
        if (size == -1) //not indexable
            throw PrimitiveFailed;
        
        return pos32BitIntFor(size); 
    }
    
    private Object primitiveAt(boolean cameFromAtBytecode, boolean convertChars, boolean includeInstVars) 
    {
        //Returns result of at: or sets success false
        SqueakObject array = stackNonInteger(1);
        int index= stackPos32BitValue(0); //note non-int returns zero

        AtCacheInfo info;
        if (cameFromAtBytecode) 
        {
            // fast entry checks cache
            info= atCache[array.hashCode() & atCacheMask];
            if (info.array != array)
                throw PrimitiveFailed;
        }
        else  
        {
            // slow entry installs in cache if appropriate
            if (array.format==6 && isA(array,Squeak.splOb_ClassFloat)) 
            {
                // hack to make Float hash work
                long floatBits= Double.doubleToRawLongBits(array.getFloatBits());
                if (index==1) 
                    return pos32BitIntFor((int)(floatBits>>>32));
                if (index==2) 
                    return pos32BitIntFor((int)(floatBits&0xFFFFFFFF));
                
                throw PrimitiveFailed;
            }
            info= makeCacheInfo(atCache, vm.specialSelectors[32], array, convertChars, includeInstVars); 
        }
        if (index<1 || index>info.size)
            throw PrimitiveFailed;
        
        if (includeInstVars)  //pointers...   instVarAt and objectAt
            return array.pointers[index-1];
        if (array.format<6)   //pointers...   normal at:
            return array.pointers[index-1+info.ivarOffset];
        if (array.format<8)   // words... 
        {
            int value= ((int[])array.bits)[index-1];
            return pos32BitIntFor(value);
        }
        if (array.format<12)  // bytes... 
        {
            int value= (((byte[])array.bits)[index-1]) & 0xFF;
            if (info.convertChars) 
                return charFromInt(value);
			else
				return SqueakVM.smallFromInt(value); 
        }
        // methods (format>=12) must simulate Squeak's method indexing
        int offset= array.pointersSize()*4;
        if (index-1-offset < 0) //reading lits as bytes
            throw PrimitiveFailed;
        
        return SqueakVM.smallFromInt((((byte[])array.bits)[index-1-offset]) & 0xFF); 
    }
    
    SqueakObject charFromInt(int ascii) 
    {
        SqueakObject charTable= (SqueakObject)vm.specialObjects[Squeak.splOb_CharacterTable];
        return charTable.getPointerNI(ascii); 
    }

    /**
     * @return result of at:put:
     */
    private Object primitiveAtPut(boolean cameFromAtBytecode, boolean convertChars, boolean includeInstVars) 
    {
        SqueakObject array = stackNonInteger(2);
        int index= stackPos32BitValue(1); //note non-int returns zero

        AtCacheInfo info;
        if (cameFromAtBytecode) 
        {
            // fast entry checks cache
            info= atPutCache[array.hashCode() & atCacheMask];
            if (info.array != array)
                throw PrimitiveFailed;
        }
        else
        {
            // slow entry installs in cache if appropriate
            info= makeCacheInfo(atPutCache, vm.specialSelectors[34], array, convertChars, includeInstVars); 
        }
        if (index<1 || index>info.size)
            throw PrimitiveFailed;

        Object objToPut= vm.stackValue(0);
        if (includeInstVars) 
        {
            // pointers...   instVarAtPut and objectAtPut
            array.pointers[index-1]= objToPut; //eg, objectAt:
            return objToPut; 
        }
        if (array.format<6)
        {
            // pointers...   normal atPut
            array.pointers[index-1+info.ivarOffset]= objToPut;
            return objToPut; 
        }
        int intToPut;
        if (array.format<8)
        {
            // words...
            intToPut= stackPos32BitValue(0);

            ((int[])array.bits)[index-1]= intToPut;
            return objToPut; 
        }
        // bytes...
        if (info.convertChars) 
        {
            // put a character...
            if (SqueakVM.isSmallInt(objToPut))
                throw PrimitiveFailed;

            SqueakObject sqObjToPut= (SqueakObject)objToPut;
            if ((sqObjToPut.sqClass != vm.specialObjects[Squeak.splOb_ClassCharacter]))
                throw PrimitiveFailed;

            Object asciiToPut= sqObjToPut.getPointer(0);
            if (!(SqueakVM.isSmallInt(asciiToPut)))
                throw PrimitiveFailed;

            intToPut= SqueakVM.intFromSmall(((Integer)asciiToPut)); 
        }
        else 
        {
            // put a byte...
            if (!(SqueakVM.isSmallInt(objToPut)))
                throw PrimitiveFailed;

            intToPut= SqueakVM.intFromSmall(((Integer)objToPut)); 
        }
        if (intToPut<0 || intToPut>255)
            throw PrimitiveFailed;

        if (array.format<8)
        {
            // bytes...
            ((byte[])array.bits)[index-1]= (byte)intToPut;
            return objToPut; 
        }
        // methods (format>=12) must simulate Squeak's method indexing
        int offset= array.pointersSize()*4;
        if (index-1-offset < 0)
            throw PrimitiveFailed;   //writing lits as bytes 

        ((byte[])array.bits)[index-1-offset]= (byte)intToPut;
        return objToPut; 
    }
    
    // FIXME: is this the same as SqueakObject.instSize() ?
    private int indexableSize(Object obj) 
    {
        if (SqueakVM.isSmallInt(obj)) 
            return -1; // -1 means not indexable
        SqueakObject sqObj= (SqueakObject) obj;
        short fmt= sqObj.format;
        if (fmt<2)
            return -1; //not indexable
        if (fmt==3 && vm.isContext(sqObj)) 
            return sqObj.getPointerI(Squeak.Context_stackPointer).intValue();
        if (fmt<6) 
            return sqObj.pointersSize() - sqObj.instSize(); // pointers
        if (fmt<12)
            return sqObj.bitsSize(); // words or bytes
        return sqObj.bitsSize() + (4*sqObj.pointersSize());  // methods
    }
    
    private SqueakObject primitiveStringReplace() 
    {
        SqueakObject dst= (SqueakObject)vm.stackValue(4);
        int dstPos= stackInteger(3)-1;
        int count= stackInteger(2) - dstPos;
        //  if (count<=0) {success= false; return dst; } //fail for compat, later succeed
        SqueakObject src= (SqueakObject)vm.stackValue(1);
        int srcPos= stackInteger(0)-1;
        short srcFmt= src.format;
        short dstFmt= dst.format;
        if (dstFmt < 8)
            if (dstFmt != srcFmt) //incompatible formats
                throw PrimitiveFailed;
            else if ((dstFmt&0xC) != (srcFmt&0xC)) //incompatible formats
                throw PrimitiveFailed;
        if (srcFmt<4) 
        {
            //pointer type objects
            int totalLength= src.pointersSize();
            int srcInstSize= src.instSize();
            srcPos+= srcInstSize;
            if ((srcPos < 0) || (srcPos + count) > totalLength)  //would go out of bounds
                throw PrimitiveFailed;
            
            totalLength= dst.pointersSize();
            int dstInstSize= dst.instSize();
            dstPos+= dstInstSize;
            if ((dstPos < 0) || (dstPos + count) > totalLength)  //would go out of bounds
                throw PrimitiveFailed;
            
            System.arraycopy(src.pointers, srcPos, dst.pointers, dstPos, count);
            return dst; 
        }
        else 
        {
            //bits type objects
            int totalLength= src.bitsSize();
            if ((srcPos < 0) || (srcPos + count) > totalLength)  //would go out of bounds
                throw PrimitiveFailed;
            totalLength= dst.bitsSize();
            if ((dstPos < 0) || (dstPos + count) > totalLength)  //would go out of bounds
                throw PrimitiveFailed;
            System.arraycopy(src.bits, srcPos, dst.bits, dstPos, count);
            return dst; 
        }
    }
        
    private boolean primitiveNext()  //Not yet implemented... 
    {
        // PrimitiveNext should succeed only if the stream's array is in the atCache.
        // Otherwise failure will lead to proper message lookup of at: and
        // subsequent installation in the cache if appropriate."
        SqueakObject stream= stackNonInteger(0);
        Object[] streamBody= stream.pointers;
        if (streamBody == null || streamBody.length < (Squeak.Stream_limit+1))
            return false;
        Object array= streamBody[Squeak.Stream_array];
        if (SqueakVM.isSmallInt(array)) 
            return false;
        int index= checkSmallInt(streamBody[Squeak.Stream_position]);
        int limit= checkSmallInt(streamBody[Squeak.Stream_limit]);
        int arraySize= indexableSize(array);
        if (index >= limit) 
            return false;
        //  (index < limit and: [(atCache at: atIx+AtCacheOop) = array])
        //      ifFalse: [^ self primitiveFail].
        //
        //  "OK -- its not at end, and the array is in the cache"
        //  index _ index + 1.
        //  result _ self commonVariable: array at: index cacheIndex: atIx.
        //  "Above may cause GC, so can't use stream, array etc. below it"
        //  successFlag ifTrue:
        //      [stream _ self stackTop.
        //      self storeInteger: StreamIndexIndex ofObject: stream withValue: index.
        //      ^ self pop: 1 thenPush: result].
        return false; 
    }
    
    private SqueakObject primitiveBlockCopy() 
    {
        Object rcvr= vm.stackValue(1);
        if (SqueakVM.isSmallInt(rcvr))
            throw PrimitiveFailed;

        Object sqArgCount= vm.top();
        if (!(SqueakVM.isSmallInt(sqArgCount)))
            throw PrimitiveFailed;
        
        SqueakObject homeCtxt= (SqueakObject) rcvr;
        if (!vm.isContext(homeCtxt))
            throw PrimitiveFailed;
        
        if (SqueakVM.isSmallInt(homeCtxt.getPointer(Squeak.Context_method)))
        {
            // ctxt is itself a block; get the context for its enclosing method
            homeCtxt= homeCtxt.getPointerNI(Squeak.BlockContext_home);
        }
        int blockSize= homeCtxt.pointersSize() - homeCtxt.instSize(); //can use a const for instSize
        SqueakObject newBlock= vm.instantiateClass(((SqueakObject)vm.specialObjects[Squeak.splOb_ClassBlockContext]),blockSize);
        Integer initialPC= vm.encodeSqueakPC(vm.pc+2,vm.method); //*** check this...
        newBlock.setPointer(Squeak.BlockContext_initialIP,initialPC);
        newBlock.setPointer(Squeak.Context_instructionPointer,initialPC);// claim not needed; value will set it
        newBlock.setPointer(Squeak.Context_stackPointer,SqueakVM.smallFromInt(0));
        newBlock.setPointer(Squeak.BlockContext_argumentCount,sqArgCount);
        newBlock.setPointer(Squeak.BlockContext_home,homeCtxt);
        newBlock.setPointer(Squeak.Context_sender,vm.nilObj);
        return newBlock; 
    }
    
    private void primitiveBlockValue(int argCount) 
    {
        Object rcvr= vm.stackValue(argCount);
        if (!isA(rcvr,Squeak.splOb_ClassBlockContext))
            throw PrimitiveFailed;

        SqueakObject block= (SqueakObject) rcvr;
        Object blockArgCount= block.getPointer(Squeak.BlockContext_argumentCount);
        if (!SqueakVM.isSmallInt(blockArgCount)) 
            throw PrimitiveFailed;
        if ((((Integer)blockArgCount).intValue()!= argCount)) 
            throw PrimitiveFailed;
        if (block.getPointer(Squeak.BlockContext_caller) != vm.nilObj) 
            throw PrimitiveFailed;
        System.arraycopy((Object)vm.activeContext.pointers,vm.sp-argCount+1,(Object)block.pointers,Squeak.Context_tempFrameStart,argCount);
        Integer initialIP= block.getPointerI(Squeak.BlockContext_initialIP);
        block.setPointer(Squeak.Context_instructionPointer,initialIP);
        block.setPointer(Squeak.Context_stackPointer,new Integer(argCount));
        block.setPointer(Squeak.BlockContext_caller,vm.activeContext);
        vm.popN(argCount+1);
        vm.newActiveContext(block);
    }
    
    private Object primitiveHash() 
    {
        Object rcvr= vm.top();
        if (SqueakVM.isSmallInt(rcvr))
            throw PrimitiveFailed;

        return new Integer(((SqueakObject)rcvr).hash); 
    }
    
    private Object setLowSpaceThreshold() 
    {
        int nBytes= stackInteger(0);
        vm.lowSpaceThreshold= nBytes;
        return vm.stackValue(1); 
    }
    
    // Scheduler Primitives
    private SqueakObject getScheduler() 
    {
        SqueakObject assn= (SqueakObject)vm.specialObjects[Squeak.splOb_SchedulerAssociation];
        return assn.getPointerNI(Squeak.Assn_value); 
    }
    
    private void processResume() 
    {
        SqueakObject process= (SqueakObject)vm.top();
        resume(process); 
    }
    
    private void processSuspend() 
    {
        SqueakObject activeProc= getScheduler().getPointerNI(Squeak.ProcSched_activeProcess);
        if (vm.top() != activeProc) 
            throw PrimitiveFailed;
        
        vm.popNandPush(1,vm.nilObj);
        transferTo(pickTopProcess());
    }
    
    private boolean isA(Object obj, int knownClass) 
    {
        Object itsClass= vm.getClass(obj);
        return itsClass == vm.specialObjects[knownClass]; 
    }
    
    private boolean isKindOf(Object obj, int knownClass) 
    {
        Object classOrSuper= vm.getClass(obj);
        Object theClass= vm.specialObjects[knownClass];
        while(classOrSuper != vm.nilObj) 
        {
            if (classOrSuper == theClass) 
                return true;
            classOrSuper= ((SqueakObject)classOrSuper).pointers[Squeak.Class_superclass];
        }
        return false; 
    }
    
    private void semaphoreWait() 
    {
        SqueakObject sema= (SqueakObject)vm.top();
        if (!isA(sema,Squeak.splOb_ClassSemaphore))
            throw PrimitiveFailed;
        int excessSignals= sema.getPointerI(Squeak.Semaphore_excessSignals).intValue();
        if (excessSignals > 0)
        {
            sema.setPointer(Squeak.Semaphore_excessSignals,SqueakVM.smallFromInt(excessSignals-1));
        }
        else 
        {
            SqueakObject activeProc= getScheduler().getPointerNI(Squeak.ProcSched_activeProcess);
            linkProcessToList(activeProc, sema);
            transferTo(pickTopProcess()); 
        }
    }
    
    private void semaphoreSignal() 
    {
        SqueakObject sema= (SqueakObject)vm.top();
        if (!isA(sema,Squeak.splOb_ClassSemaphore))
            throw PrimitiveFailed;
        synchronousSignal(sema);
    }
    
    void synchronousSignal(SqueakObject sema) 
    {
        if (isEmptyList(sema)) 
        {
            //no process is waiting on this semaphore"
            int excessSignals= sema.getPointerI(Squeak.Semaphore_excessSignals).intValue();
            sema.setPointer(Squeak.Semaphore_excessSignals,SqueakVM.smallFromInt(excessSignals+1)); 
        }
        else 
        {
            resume(removeFirstLinkOfList(sema));
        }
        return; 
    }

    private void resume(SqueakObject newProc) 
    {
        SqueakObject activeProc= getScheduler().getPointerNI(Squeak.ProcSched_activeProcess);
        int activePriority= activeProc.getPointerI(Squeak.Proc_priority).intValue();
        int newPriority= newProc.getPointerI(Squeak.Proc_priority).intValue();
        if (newPriority > activePriority) 
        {
            putToSleep(activeProc);
            transferTo(newProc); 
        }
        else 
        {
            putToSleep(newProc); 
        }
    }
    
    private void putToSleep(SqueakObject aProcess) 
    {
        //Save the given process on the scheduler process list for its priority.
        int priority= aProcess.getPointerI(Squeak.Proc_priority).intValue();
        SqueakObject processLists=  getScheduler().getPointerNI(Squeak.ProcSched_processLists);
        SqueakObject processList= processLists.getPointerNI(priority - 1);
        linkProcessToList(aProcess, processList); 
    }
    
    private void transferTo(SqueakObject newProc) 
    {
        //Record a process to be awakened on the next interpreter cycle.
        SqueakObject sched=  getScheduler();
        SqueakObject oldProc= sched.getPointerNI(Squeak.ProcSched_activeProcess);
        sched.setPointer(Squeak.ProcSched_activeProcess,newProc);
        oldProc.setPointer(Squeak.Proc_suspendedContext,vm.activeContext);
        //int prio= vm.intFromSmall((Integer)newProc.pointers[Squeak.Proc_priority]);
        //System.err.println("Transfer to priority " + prio + " at byteCount " + vm.byteCount);
        //if (prio==8)
        //    vm.dumpStack();
        vm.newActiveContext(newProc.getPointerNI(Squeak.Proc_suspendedContext));
        //System.err.println("new pc is " + vm.pc + "; method offset= " + ((vm.method.pointers.length+1)*4));
        newProc.setPointer(Squeak.Proc_suspendedContext,vm.nilObj);
        vm.reclaimableContextCount= 0; 
    }
    
    private SqueakObject pickTopProcess()  // aka wakeHighestPriority 
    {
        //Return the highest priority process that is ready to run.
        //Note: It is a fatal VM error if there is no runnable process.
        SqueakObject schedLists= getScheduler().getPointerNI(Squeak.ProcSched_processLists);
        int p= schedLists.pointersSize() - 1;  // index of last indexable field"
        p= p - 1;
        SqueakObject processList= schedLists.getPointerNI(p);
        while(isEmptyList(processList)) 
        {
            p= p - 1;
            if (p < 0) 
                return vm.nilObj; //self error: 'scheduler could not find a runnable process' ].
            processList= schedLists.getPointerNI(p); 
        }
        return removeFirstLinkOfList(processList); 
    }    
    
    private void linkProcessToList(SqueakObject proc, SqueakObject aList) 
    {
        // Add the given process to the given linked list and set the backpointer
        // of process to its new list."
        if (isEmptyList(aList))
        {
            aList.setPointer(Squeak.LinkedList_firstLink,proc);
        }
        else
        {
            SqueakObject lastLink= aList.getPointerNI(Squeak.LinkedList_lastLink);
            lastLink.setPointer(Squeak.Link_nextLink,proc); 
        }
        aList.setPointer(Squeak.LinkedList_lastLink,proc);
        proc.setPointer(Squeak.Proc_myList,aList); 
    }
        
    private boolean isEmptyList(SqueakObject aLinkedList) 
    {
        return aLinkedList.getPointerNI(Squeak.LinkedList_firstLink) == vm.nilObj; 
    }
    
    private SqueakObject removeFirstLinkOfList(SqueakObject aList) 
    {
        //Remove the first process from the given linked list."
        SqueakObject first= aList.getPointerNI(Squeak.LinkedList_firstLink);
        SqueakObject last= aList.getPointerNI(Squeak.LinkedList_lastLink);
        if (first == last) 
        {
            aList.setPointer(Squeak.LinkedList_firstLink,vm.nilObj);
            aList.setPointer(Squeak.LinkedList_lastLink,vm.nilObj); 
        }
        else 
        {
            SqueakObject next= first.getPointerNI(Squeak.Link_nextLink);
            aList.setPointer(Squeak.LinkedList_firstLink,next); 
        }
        first.setPointer(Squeak.Link_nextLink,vm.nilObj);
        return first; 
    }
    
    private SqueakObject registerSemaphore(int specialObjSpec) 
    {
        SqueakObject sema= (SqueakObject)vm.top();
        if (isA(sema,Squeak.splOb_ClassSemaphore))
            vm.specialObjects[specialObjSpec]= sema;
        else
            vm.specialObjects[specialObjSpec]= vm.nilObj;
        return (SqueakObject)vm.stackValue(1); 
    }
    
    private Object primitiveSignalAtMilliseconds()  //Delay signal:atMs: 
    {
        int msTime= stackInteger(0);
        Object sema= stackNonInteger(1);
        Object rcvr= stackNonInteger(2);

        //System.err.println("Signal at " + msTime);
        //vm.dumpStack();
        if (isA(sema,Squeak.splOb_ClassSemaphore)) 
        {
            vm.specialObjects[Squeak.splOb_TheTimerSemaphore]= sema;
            vm.nextWakeupTick= msTime; 
        }
        else 
        {
            vm.specialObjects[Squeak.splOb_TheTimerSemaphore]= vm.nilObj;
            vm.nextWakeupTick= 0; 
        }
        return rcvr; 
    }  

    //Other Primitives
    
    private Integer millisecondClockValue() 
    {
        //Return the value of the millisecond clock as an integer.
        //Note that the millisecond clock wraps around periodically.
        //The range is limited to SmallInteger maxVal / 2 to allow
        //delays of up to that length without overflowing a SmallInteger."
        return SqueakVM.smallFromInt(((int) (System.currentTimeMillis() & (long)(SqueakVM.maxSmallInt>>1)))); 
    }
    
    private void beDisplay(SqueakObject displayObj) 
    {
        SqueakVM.FormCache disp= vm.newFormCache(displayObj);
        if (disp.squeakForm==null) 
            throw PrimitiveFailed;
        vm.specialObjects[Squeak.splOb_TheDisplay]= displayObj;
        displayBitmap= disp.bits;
        boolean remap= theDisplay != null;
        if (remap) 
        {
            Dimension requestedExtent= new Dimension(disp.width, disp.height);
            if (!theDisplay.getExtent().equals(requestedExtent)) 
            {
                System.err.println("Squeak: changing screen size to " + disp.width + "@" + disp.height);
                theDisplay.setExtent(requestedExtent); 
            }
        }
        else 
        {
            theDisplay= new Screen("Squeak", disp.width,disp.height,disp.depth,vm);
            theDisplay.getFrame().addWindowListener(new WindowAdapter() 
            {
                public void windowClosing(WindowEvent evt) 
                {
                    // TODO ask before shutdown
                    // FIXME at least lock out quitting until concurrent image save has finished
                	theDisplay.exit();
                }
            }
            );
        }
        displayBitmapInBytes= new byte[displayBitmap.length*4];
        copyBitmapToByteArray(displayBitmap,displayBitmapInBytes,
                              new Rectangle(0,0,disp.width,disp.height),disp.pitch,disp.depth);
        theDisplay.setBits(displayBitmapInBytes, disp.depth);
        if (!remap) 
            theDisplay.open();
    }

    private void beCursor(int argCount) 
    {
        // For now we ignore the white outline form (maskObj)
        if (theDisplay == null) 
            return;
        SqueakObject cursorObj, maskObj;
        if (argCount==0) 
        {
            cursorObj= stackNonInteger(0);
            maskObj= vm.nilObj; 
        }
        else 
        {
            cursorObj= stackNonInteger(1);
            maskObj= stackNonInteger(0); 
        }
        SqueakVM.FormCache cursorForm= vm.newFormCache(cursorObj);
        if ( cursorForm.squeakForm == null ) 
            throw PrimitiveFailed;
        //Following code for offset is not yet used...
        SqueakObject offsetObj= checkNonSmallInt(cursorObj.getPointer(4)); 
        if ( !isA(offsetObj,Squeak.splOb_ClassPoint))
            throw PrimitiveFailed;
        int offsetX = checkSmallInt(offsetObj.pointers[0]);
        int offsetY = checkSmallInt(offsetObj.pointers[1]);
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
    }
    
    private void primitiveYield(int numArgs) 
    {
        // halts execution until EHT callbacks notify us
        long millis= 100;
        if (numArgs > 1)
            throw PrimitiveFailed;
        if (numArgs > 0) 
        {
            // But, for now, wait time is ignored...
            int micros= stackInteger(0);
            vm.pop();
            millis= micros/1000; 
        }
        // try { synchronized (vm) { vm.wait(); }
        //         } catch (InterruptedException e) { }
        // TODO how to handle third-party interruptions?
        try 
        {
            synchronized(SqueakVM.inputLock) 
            {
                while(!vm.screenEvent) SqueakVM.inputLock.wait(millis);
            }
        }
        catch(InterruptedException e) {}
    }
    
    private void primitiveCopyBits(SqueakObject rcvr, int argCount)
    {
        // no rcvr class check, to allow unknown subclasses (e.g. under Turtle)
        if (!bitbltTable.loadBitBlt(rcvr, argCount, false, (SqueakObject)vm.specialObjects[Squeak.splOb_TheDisplay])) 
            throw PrimitiveFailed;
        
        Rectangle affectedArea= bitbltTable.copyBits();
        if (affectedArea != null && theDisplay!=null) 
        {
            copyBitmapToByteArray(displayBitmap,displayBitmapInBytes,affectedArea,
                                  bitbltTable.dest.pitch,bitbltTable.dest.depth);
            theDisplay.redisplay(false, affectedArea); 
        }
        if (bitbltTable.combinationRule == 22 || bitbltTable.combinationRule == 32)
            vm.popNandPush(2,SqueakVM.smallFromInt(bitbltTable.bitCount));
    }
    
    private void copyBitmapToByteArray(int[] words, byte[] bytes,Rectangle rect, int raster, int depth) 
    {
        //Copy our 32-bit words into a byte array  until we find out
        // how to make AWT happy with int buffers
        int word;
        int ix1= rect.x/depth/32;
        int ix2= (rect.x+rect.width-1)/depth/32 + 1;
        for (int y=rect.y; y<rect.y+rect.height; y++) 
        {
            int iy= y*raster;
            for(int ix=ix1; ix<ix2; ix++) 
            {
                word= (words[iy+ix])^BWMask;
                for(int j=0; j<4; j++)
                    bytes[((iy+ix)*4)+j]= (byte)((word>>>((3-j)*8))&255); 
            } 
        }
        
        //        int word;
        //        for(int i=0; i<words.length; i++) {
        //            word= ~(words[i]);
        //            for(int j=0; j<4; j++)
        //                bytes[(i*4)+j]= (byte)((word>>>((3-j)*8))&255); }
    }
    
    private SqueakObject primitiveMousePoint() 
    {
        SqueakObject pointClass= (SqueakObject)vm.specialObjects[Squeak.splOb_ClassPoint];
        SqueakObject newPoint= vm.instantiateClass(pointClass,0);
        Point lastMouse= theDisplay.getLastMousePoint();
        newPoint.setPointer(Squeak.Point_x,SqueakVM.smallFromInt(lastMouse.x));
        newPoint.setPointer(Squeak.Point_y,SqueakVM.smallFromInt(lastMouse.y));
        return newPoint; 
    }
    
    private Integer primitiveMouseButtons() 
    {
        return SqueakVM.smallFromInt(theDisplay.getLastMouseButtonStatus()); 
    }
    
    private Object primitiveKbdNext() 
    {
        return SqueakVM.smallFromInt(theDisplay.keyboardNext()); 
    }
    
    private Object primitiveKbdPeek() 
    {
        if (theDisplay==null) 
            return vm.nilObj;
        int peeked= theDisplay.keyboardPeek();
        return peeked==0? (Object) vm.nilObj : SqueakVM.smallFromInt(peeked); 
    }
    
    private SqueakObject primitiveArrayBecome(boolean doBothWays) 
    {
        // Should flush method cache
        SqueakObject rcvr= stackNonInteger(1);
        SqueakObject arg= stackNonInteger(0);
        
        if ( image.bulkBecome(rcvr.pointers, arg.pointers, doBothWays) )
            return rcvr;    
        
        throw PrimitiveFailed;
    }
    
    private SqueakObject primitiveSomeObject() 
    {
        return image.nextInstance(0, null); 
    }
    
    private SqueakObject primitiveSomeInstance(SqueakObject sqClass) 
    {
        return image.nextInstance(0, sqClass); 
    }
    
    private Object primitiveNextObject(SqueakObject priorObject) 
    {
        SqueakObject nextObject= image.nextInstance(image.otIndexOfObject(priorObject)+1, null);
        if (nextObject==vm.nilObj)
			return SqueakVM.smallFromInt(0);
        return nextObject; 
    }
    
    private SqueakObject primitiveNextInstance(SqueakObject priorInstance) 
    {
        SqueakObject sqClass= (SqueakObject)priorInstance.sqClass;
        return image.nextInstance(image.otIndexOfObject(priorInstance)+1, sqClass); 
    }
    
    private boolean relinquishProcessor() 
    {
        int periodInMicroseconds= stackInteger(0); //NOTE: argument is *ignored*
        vm.pop();
        //        Thread.currentThread().yield();
        //        try {vm.wait(50L); } // 50 millisecond pause
        //            catch (InterruptedException e) {}
        return true; 
    }
    
    Object primSeconds() 
    {
        long currentTimeMillis = System.currentTimeMillis();
        return squeakSeconds(currentTimeMillis); 
    }

    // -- Some support methods -----------------------------------------------------------
    
    PrimitiveFailedException primitiveFailed()
    {
        return PrimitiveFailed; 
    }

    /**
     * FIXME: surely something better can be devised?
     *        Idea: make argCount a field, then this method
     *        needs no argument
     */
    Object stackReceiver( int argCount )
    {
        return vm.stackValue( argCount ); 
    }
}
