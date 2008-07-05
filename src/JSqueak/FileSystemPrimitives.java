package JSqueak;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;

/**
 * The methods in this class implement the following file system primitives:
 *
 *    "File Primitives (150-169)"
 *    (150 primitiveFileAtEnd)
 *    (151 primitiveFileClose)
 *    (152 primitiveFileGetPosition)
 *    (153 primitiveFileOpen)
 *    (154 primitiveFileRead)
 *    (155 primitiveFileSetPosition)
 *    (156 primitiveFileDelete)
 *    (157 primitiveFileSize)
 *    (158 primitiveFileWrite)
 *    (159 primitiveFileRename)
 *    (160 primitiveDirectoryCreate)
 *    (161 primitiveDirectoryDelimitor)
 *    (162 primitiveDirectoryLookup)
 *
 *
 * The primitive method comments were mostly taken from a Squeak 1.1 image, with one
 * or two coming from a Squeak 3.9 image.
 * 
 * Known issues and todo items:
 *
 *    * Lightly tested, use at your own risk!
 *    
 *    * Files are kept in a map, and they are never removed from it (so they are
 *      not being garbage collected.
 *
 *    * Unsure what to return from fileClose(), assuming self (at least that is what Squeak 3.9 does).
 *    
 *    * Unsure what to return from fileRename() and directoryCreate().  Assuming self.
 *
 *    * Cannot yet handle non-small integers.  This affects the following methods:
 *
 *      * getPosition()
 *      * readIntoStartingAtCount()
 *      * fileSize()
 *      * fileWrite()
 *
 *    * Cannot handle non-byte arrays.  This affects the methods:
 *
 *      * readIntoStartingAtCount()
 *      * fileWrite()
 *      
 *    * Debug logging (printing stack traces) needs to be cleaned up.
 */
class FileSystemPrimitives 
{
    private final SqueakPrimitiveHandler fHandler;
    
    /**
     * Map<SqueakObject, RandomAccessFile> of files keyed by ID (Squeak String).
     */
    private final Map fFiles = new HashMap();
    
    public FileSystemPrimitives( SqueakPrimitiveHandler primitiveHandler )
    {
        fHandler = primitiveHandler;
    }    

    
    // -- Primitives ----------------------------------------------------------------------

    
    /**
     * primAtEnd: id
     *    "Answer whether the receiver is currently at its end.  2/12/96 sw"
     *
     *    <primitive: 150>
     *    ^ self primitiveFailed!
     */
    Object fileAtEnd( int argCount )
    {
        if ( argCount != 1 )
            throw fHandler.primitiveFailed();
        
        RandomAccessFile file = lookupFile();
        try 
        {
            return fHandler.squeakBool( file.getFilePointer() >= file.length() );
        }
        catch ( IOException e ) 
        {
            e.printStackTrace();
        }
        
        throw fHandler.primitiveFailed();
    }

    /**
      * primClose: anID
      *         "Primitive call to close the receiver.  2/12/96 sw"
      *         <primitive: 151>
      *         ^ self primitiveFailed!    
      */
    Object fileClose( int argCount )
    {
        if ( argCount != 1 )
            throw fHandler.primitiveFailed();
        
        RandomAccessFile file = lookupFile();
        try 
        {
            file.close();
            
            // Pharo seems to return self, so let's do the same
            return fHandler.stackReceiver( 1 );
        }
        catch (IOException e) 
        {
            e.printStackTrace();
        }
        
        throw fHandler.primitiveFailed();
    }

    /**
     * primGetPosition: id
     *     "Get the receiver's current file position.  2/12/96 sw"
     *     <primitive: 152>
     *     ^ self primitiveFailed!
     */
    Object getPosition( int argCount )
    {
        if ( argCount != 1 )
            throw fHandler.primitiveFailed();
        
        RandomAccessFile file = lookupFile();
        try 
        {
            return fHandler.pos32BitIntFor( file.getFilePointer() );
        }
        catch (IOException e) 
        {
            e.printStackTrace();
        }
        
        throw fHandler.primitiveFailed();
    }
    
    /**
     * primOpen: fileName writable: writableFlag
     *     "Open a file of the given name, and return the file ID obtained.
     *     If writableFlag is true, then 
     *         if there is none with this name, then create one
     *         else prepare to overwrite the existing from the beginning
     *     otherwise
     *        if the file exists, open it read-only
     *        else return nil"
     *
     *     <primitive: 153>
     *     ^ nil
     */
    Object openWritable( int argCount ) 
    {
        if ( argCount != 2 )
            throw fHandler.primitiveFailed();

        SqueakObject fileName = fHandler.stackNonInteger( 1 );
        SqueakObject writableFlag = fHandler.stackNonInteger( 0 );
        
        String mode = fHandler.javaBool( writableFlag ) ? "rw" : "r";
        try 
        {
            RandomAccessFile file = new RandomAccessFile( fileName.asString(), mode );
            SqueakObject fileId = fHandler.makeStString( "fileId: " + fileName.asString() );
            setFile( fileId, file );
            
            return fileId; 
        }
        catch (  FileNotFoundException e ) 
        {
            System.err.println( e.getMessage() );
        }
        
        throw fHandler.primitiveFailed();
    }

    /**
     * primRead: id into: byteArray startingAt: startIndex count: count
     *    "read from the receiver's file into the given area of storage, starting at the given index, 
     *    as many as count bytes; return the number of bytes actually read.  2/12/96 sw"
     *
     *    <primitive: 154>
     *
     *    self halt: 'error reading file'!
     */
    Object readIntoStartingAtCount( int argCount )
    {
        if ( argCount != 4 )
            throw fHandler.primitiveFailed();
        
        SqueakObject byteArray = fHandler.stackNonInteger( 2 );
        int startIndex = fHandler.stackInteger( 1 ) - 1;
        int count = fHandler.stackInteger( 0 );
        
        RandomAccessFile file = lookupFile( 3 );
        
        byte[] buffer = new byte[ count ];
        try 
        {
            int read = file.read( buffer, 0, count );
            
            for ( int index = 0; index < read; index++ )
            {
                byteArray.setByte( startIndex + index, buffer[index] ); // FIXME: this code cheats!
            }
            
            return fHandler.pos32BitIntFor( read );
        } 
        catch ( IOException e ) 
        {
            e.printStackTrace();
        }
	
        throw fHandler.primitiveFailed();
    }

    /**
     * primSetPosition: id to: aNumber
     *     "Set the receiver's file position to be a Number.  2/12/96 sw"
     *     <primitive: 155>
     *     ^ self primitiveFailed!
     */
    Object fileSetPosition( int argCount ) 
    {
        if ( argCount != 2 )
            throw fHandler.primitiveFailed();
        
        RandomAccessFile file = lookupFile( 1 );
        int pos = fHandler.stackPos32BitValue( 0 );
            
        try 
        {
            file.seek( pos );
            return fHandler.stackReceiver( argCount );
        }
        catch (IOException e) 
        {
            e.printStackTrace();
        }
	
        throw fHandler.primitiveFailed();
    }

    /**
     * primDeleteFileNamed: aFileName
     *     "Delete the file of the given name. Return self if the primitive succeeds, nil otherwise."
     *     
     *     <primitive: 156>
     *     ^ nil
     */
    Object fileDelete(int argCount) 
    {
        if ( argCount != 1 )
            throw fHandler.primitiveFailed();
        
        SqueakObject fileName = fHandler.stackNonInteger( 0 );
        
        File file = new File( fileName.asString() );
        if ( file.delete() )
            return fHandler.stackReceiver( argCount ); 
        
        throw fHandler.primitiveFailed();
    }

    /**
     * primSize: id
     *    "Return the size of the receiver's file.  2/12/96 sw"
     *    <primitive: 157>
     *    ^ self primitiveFailed!
     */
    Object fileSize(int argCount) 
    {
        if ( argCount != 1 )
            throw fHandler.primitiveFailed();
        
        RandomAccessFile file = lookupFile();
        try 
        {
            return fHandler.pos32BitIntFor( file.length() );
        }
        catch ( IOException e ) 
        {
            e.printStackTrace();
        }
	
        throw fHandler.primitiveFailed();
    }

    /**
     * primWrite: id from: byteArray startingAt: startIndex count: count
     *     "Write into the receiver's file from the given area of storage, starting 
     *     at the given index, as many as count bytes; return the number of bytes 
     *     actually written. 2/12/96 sw"
     *     
     *     <primitive: 158>
     *     
     *     closed ifTrue: [^ self halt: 'Write error: File not open'].
     *     rwmode ifFalse: [^ self halt: 'Error-attempt to write to a read-only file.'].
     *     self halt: 'File write error'! !
     */
    Object fileWrite (int argCount ) 
    {
        if ( argCount != 4 )
            throw fHandler.primitiveFailed();
        
        RandomAccessFile file = lookupFile( 3 );
        SqueakObject byteArray = fHandler.stackNonInteger( 2 );
        int startIndex = fHandler.stackInteger( 1 ) - 1; // zero based
        int count = fHandler.stackInteger( 0 );
        
        try 
        {
            int written = 0;
            for ( int index = startIndex; index < startIndex + count; index++ )
            {
                file.write( byteArray.getByte( index ) );
                written++;
            }
            
            return fHandler.pos32BitIntFor( written );
        }
        catch ( IOException e ) 
        {
            e.printStackTrace();
        }
        
        throw fHandler.primitiveFailed();
    }


    /**
     * primitiveRename: oldFileName toBe: newFileName 
     *     "Rename the file of the given name if it exists, else fail"
     *     <primitive: 159>
     *     self halt: 'Attempt to rename a non-existent file,
     *     or to use a name that is already in use'!
     */
    Object fileRename( int argCount ) 
    {
        if ( argCount != 2 )
            throw fHandler.primitiveFailed();
        
        SqueakObject oldName = fHandler.stackNonInteger( 1 );
        SqueakObject newName = fHandler.stackNonInteger( 0 );
        
        File file = new File( oldName.asString() );
        if ( file.renameTo( new File( newName.asString() ) ) )
            return fHandler.stackReceiver( argCount ); 
        
        throw fHandler.primitiveFailed();
    }

    /**
     * primCreateDirectory: fullPath
     *     "Create a directory named by the given path. Fail if the path 
     *     is bad or if a file or directory by that name already exists."
     * 
     *     <primitive: 'primitiveDirectoryCreate' module: 'FilePlugin'>
     *     self primitiveFailed
     */
    Object directoryCreate( int argCount ) 
    {
        if ( argCount != 1 )
            throw fHandler.primitiveFailed();
        
        SqueakObject fullPath = fHandler.stackNonInteger( 0 );
        
        File directory = new File( fullPath.asString() );
        if ( directory.mkdir() )
            return fHandler.stackReceiver( argCount ); 
        
        throw fHandler.primitiveFailed();
    }

    /**
     * actualPathNameDelimiter
     *     "Return the path delimiter for the underlying file system."
     *     <primitive: 161>
     *     self primitiveFailed.!
     */
    SqueakObject directoryDelimitor()
    {
        return fHandler.charFromInt( (int) File.separatorChar );
    }

    /**
     * lookupEntryIn: pathName index: index
     *     "Look up the index-th entry of the directory with the given path
     *     (starting from the root of the file hierarchy) and return an array
     *     containing:
     *     
     *     <name> <creationTime> <modificationTime> <dirFlag> <fileSize>
     *     
     *     The creation and modification times are in seconds since the start
     *     of the Smalltalk time epoch. DirFlag is true if the entry is a
     *     directory. FileSize the file size in bytes or zero for directories.
     *     The primitive returns nil when index is past the end of the directory.
     *     It fails if the given pathName is bad."
     *     
     *     <primitive: 162>
     *     self primitiveFailed.!
     */
    Object lookupEntryInIndex( int argCount ) 
    {
        if ( argCount != 2 )
            throw fHandler.primitiveFailed();
        
        SqueakObject fullPath = fHandler.stackNonInteger( 1 );
        int index = fHandler.stackInteger( 0 ) - 1;
        
        if ( index < 0 )
            throw fHandler.primitiveFailed();
        
        String filename = fullPath.asString();
        if ( filename.trim().length() == 0 )
            filename = "/";
        
        File directory = new File( fullPath.asString() );
        if ( ! directory.exists() )
            throw fHandler.primitiveFailed();
        
        File[] paths = directory.listFiles();
        if ( index < paths.length )
            return makeDirectoryEntryArray( paths[index] );
        
        return fHandler.squeakNil();
    }

    /**
     * Array with
     *
     *    <name> <creationTime> <modificationTime> <dirFlag> <fileSize>
     *
     * See {@link #lookupEntryInIndex(int)}
     */
    private SqueakObject makeDirectoryEntryArray( File file ) 
    {
        // bah, Java doesn't provide the creation time.  If it is
        // really necessary, it may be possible to use java-posix or
        // jtux.
        String name = file.getName();
        long creationTime = file.lastModified(); 
        long modificationTime = file.lastModified();
        boolean dirFlag = file.isDirectory();
        long fileSize = file.length();
        
        Object[] array = { fHandler.makeStString( name ),
                fHandler.squeakSeconds( creationTime ),
                fHandler.squeakSeconds( modificationTime ),
                fHandler.squeakBool( dirFlag ),
                fHandler.pos32BitIntFor( fileSize ) };
        
        return fHandler.squeakArray( array );
    }

    // -- Support methods -----------------------------------------------------------------

    
    private void setFile( SqueakObject fileId, RandomAccessFile file )
    {
        fFiles.put( fileId, file );
    }

    /**
     * @return the file associated with the file ID at depth 0 of the argument
     */
    private RandomAccessFile lookupFile()
    {
        return lookupFile( 0 );
    }
    
    /**
     * @return the file associated with the file ID in the given stack position
     */
    private RandomAccessFile lookupFile( int stackDepth )
    {
        SqueakObject fileId = fHandler.stackNonInteger( stackDepth );
        if ( ! fFiles.containsKey( fileId ) )
            throw fHandler.primitiveFailed(); 
            
        return (RandomAccessFile) fFiles.get( fileId );
    }
}
