/*
Squeak.java
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

/**
 * @author Dan Ingalls
 *
 */
public interface Squeak {
	
	// Squeak Headers
	public final static int HeaderTypeMask= 3;
	public final static int HeaderTypeSizeAndClass= 0; //3-word header
	public final static int HeaderTypeClass= 1;        //2-word header
	public final static int HeaderTypeFree= 2;         //free block
	public final static int HeaderTypeShort= 3;        //1-word header

        //Indices into SpecialObjects array
        public final static int splOb_NilObject= 0;
	public final static int splOb_FalseObject= 1;
	public final static int splOb_TrueObject= 2;
	public final static int splOb_SchedulerAssociation= 3;
	public final static int splOb_ClassBitmap= 4;
	public final static int splOb_ClassInteger= 5;
	public final static int splOb_ClassString= 6;
	public final static int splOb_ClassArray= 7;
	//public final static int splOb_SmalltalkDictionary= 8;  old slot 8
	public final static int splOb_ClassFloat= 9;
	public final static int splOb_ClassMethodContext= 10;
	public final static int splOb_ClassBlockContext= 11;
	public final static int splOb_ClassPoint= 12;
	public final static int splOb_ClassLargePositiveInteger= 13;
	public final static int splOb_TheDisplay= 14;
	public final static int splOb_ClassMessage= 15;
	public final static int splOb_ClassCompiledMethod= 16;
	public final static int splOb_TheLowSpaceSemaphore= 17;
	public final static int splOb_ClassSemaphore= 18;
	public final static int splOb_ClassCharacter= 19;
	public final static int splOb_SelectorDoesNotUnderstand= 20;
	public final static int splOb_SelectorCannotReturn= 21;
	public final static int splOb_TheInputSemaphore= 22;
	public final static int splOb_SpecialSelectors= 23;
	public final static int splOb_CharacterTable= 24;
	public final static int splOb_SelectorMustBeBoolean= 25;
	public final static int splOb_ClassByteArray= 26;
	public final static int splOb_ClassProcess= 27;
	public final static int splOb_CompactClasses= 28;
	public final static int splOb_TheTimerSemaphore= 29;
	public final static int splOb_TheInterruptSemaphore= 30;
	public final static int splOb_FloatProto= 31;
	public final static int splOb_SelectorCannotInterpret= 34;
	public final static int splOb_MethodContextProto= 35;
	public final static int splOb_BlockContextProto= 37;
	public final static int splOb_ExternalObjectsArray= 38;
	public final static int splOb_ClassPseudoContext= 39;
	public final static int splOb_ClassTranslatedMethod= 40;
	public final static int splOb_TheFinalizationSemaphore= 41;
	public final static int splOb_ClassLargeNegativeInteger= 42;
	public final static int splOb_ClassExternalAddress= 43;
	public final static int splOb_ClassExternalStructure= 44;
	public final static int splOb_ClassExternalData= 45;
	public final static int splOb_ClassExternalFunction= 46;
	public final static int splOb_ClassExternalLibrary= 47;
	public final static int splOb_SelectorAboutToReturn= 48;
	
	
	// Class layout:
	public final static int Class_superclass= 0;
	public final static int Class_mdict= 1;
	public final static int Class_format= 2;
	public final static int Class_name= 6;
	
        // Context layout
	public final static int Context_sender= 0;
	public final static int Context_instructionPointer= 1;
	public final static int Context_stackPointer= 2;
	public final static int Context_method= 3;
	public final static int Context_receiver= 5;
	public final static int Context_tempFrameStart= 6;
	public final static int Context_smallFrameSize= 17;
	public final static int Context_largeFrameSize= 57;
	public final static int BlockContext_caller= 0;
	public final static int BlockContext_argumentCount= 3;
	public final static int BlockContext_initialIP= 4;
	public final static int BlockContext_home= 5;
        // Stream layout:
	public final static int Stream_array= 0;
	public final static int Stream_position= 1;
	public final static int Stream_limit= 2;
	//Class ProcessorScheduler"
	public final static int ProcSched_processLists= 0;
	public final static int ProcSched_activeProcess= 1;
	//Class Link"
	public final static int Link_nextLink= 0;
	//Class LinkedList"
	public final static int LinkedList_firstLink= 0;
	public final static int LinkedList_lastLink= 1;
	//Class Semaphore"
	public final static int Semaphore_excessSignals= 2;
	//Class Process"
	public final static int Proc_suspendedContext= 1;
	public final static int Proc_priority= 2;
	public final static int Proc_myList= 3;	
	// Association layout:
	public final static int Assn_key= 0;
	public final static int Assn_value= 1;
	// MethodDict layout:
	public final static int MethodDict_array= 1;
	public final static int MethodDict_selectorStart= 2;
        // Message layout
	public final static int Message_selector= 0;
	public final static int Message_arguments= 1;
	public final static int Message_lookupClass= 2;
	// Point layout:
	public final static int Point_x= 0;
	public final static int Point_y= 1;
	// Largetinteger layout:
	public final static int Largeinteger_bytes= 0;
	public final static int Largeinteger_neg= 1;
	// Bitblt layout:
	public final static int Bitblt_function= 0;
	public final static int Bitblt_gray= 1;
	public final static int Bitblt_destbits= 2;
	public final static int Bitblt_destraster= 3;
	public final static int Bitblt_destx= 4;
	public final static int Bitblt_desty= 5;
	public final static int Bitblt_width= 6;
	public final static int Bitblt_height= 7;
	public final static int Bitblt_sourcebits= 8;
	public final static int Bitblt_sourceraster= 9;
	public final static int Bitblt_sourcex= 10;
	public final static int Bitblt_sourcey= 11;
	public final static int Bitblt_clipx= 12;
	public final static int Bitblt_clipy= 13;
	public final static int Bitblt_clipwidth= 14;
	public final static int Bitblt_clipheight= 15;
	public final static int Bitblt_sourcefield= 16;
	public final static int Bitblt_destfield= 17;
	public final static int Bitblt_source= 18;
	public final static int Bitblt_dest= 19;
	// Form layout:
	public final static int Form_bits= 0;
	public final static int Form_width= 1;
	public final static int Form_height= 2;
	public final static int Form_depth= 3;

}
