/*
 * Copyright 2007 ETH Zuerich, CISD.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ch.systemsx.cisd.hdf5;

import static ch.systemsx.cisd.hdf5.HDF5Utils.*;
import static ncsa.hdf.hdf5lib.HDF5Constants.*;

import java.io.File;
import java.lang.reflect.Array;
import java.util.BitSet;
import java.util.Date;

import ncsa.hdf.hdf5lib.HDF5Constants;
import ncsa.hdf.hdf5lib.HDFNativeData;
import ncsa.hdf.hdf5lib.exceptions.HDF5JavaException;

import ch.systemsx.cisd.common.array.MDArray;
import ch.systemsx.cisd.common.array.MDByteArray;
import ch.systemsx.cisd.common.array.MDDoubleArray;
import ch.systemsx.cisd.common.array.MDFloatArray;
import ch.systemsx.cisd.common.array.MDIntArray;
import ch.systemsx.cisd.common.array.MDLongArray;
import ch.systemsx.cisd.common.array.MDShortArray;
import ch.systemsx.cisd.common.process.ICallableWithCleanUp;
import ch.systemsx.cisd.common.process.ICleanUpRegistry;
import ch.systemsx.cisd.hdf5.HDF5.StorageLayout;

/**
 * A class for writing HDF5 files (HDF5 1.6.x or HDF5 1.8.x).
 * <p>
 * The class focuses on ease of use instead of completeness. As a consequence not all valid HDF5
 * files can be generated using this class, but only a subset.
 * <p>
 * <em>Note: The writer needs to be opened (call to {@link #open()}) before being used and needs 
 * to be closed (call to {@link #close()}) when finished. <b>Without calling {@link #close()} 
 * the content is not guaranteed to be written to disk completely.</b> Note that you can call 
 * {@link #flush()} to ensure the current state of the content is written to disk.</em>
 * <p>
 * The configuration of the writer is done by chaining calls to methods {@link #overwrite()},
 * {@link #dontUseExtendableDataTypes()} and {@link #useLatestFileFormat()} before calling
 * {@link #open()}.
 * <p>
 * Usage:
 * 
 * <pre>
 * float[] f = new float[100];
 * ...
 * HDF5Writer writer = new HDF5Writer(&quot;test.h5&quot;).open();
 * writer.writeFloatArray(&quot;/some/path/dataset&quot;, f);
 * writer.addAttribute(&quot;some key&quot;, &quot;some value&quot;);
 * writer.close();
 * </pre>
 * 
 * @author Bernd Rinn
 */
public final class HDF5Writer extends HDF5Reader implements HDF5SimpleWriter
{
    /**
     * A constant that specifies the default deflation level (gzip compression).
     */
    private final static int DEFAULT_DEFLATION = 6;

    /**
     * The size threshold for the COMPACT storage layout.
     */
    private final static int COMPACT_LAYOUT_THRESHOLD = 256;

    private boolean useExtentableDataTypes = true;

    private boolean overwrite = false;

    private boolean useLatestFileFormat = false;

    private int variableLengthStringDataTypeId;

    /**
     * Opens an HDF5 file for reading and writing. The file will be created if it doesn't exist.
     * 
     * @param hdf5File The HDF5 file to open.
     */
    public HDF5Writer(File hdf5File)
    {
        super(hdf5File);
    }

    @Override
    protected void commitDataType(final String dataTypePath, final int dataTypeId)
    {
        h5.commitDataType(fileId, dataTypePath, dataTypeId);
    }

    // /////////////////////
    // Configuration
    // /////////////////////

    /**
     * The file will be truncated to length 0 if it already exists, that is its content will be
     * deleted.
     */
    public HDF5Writer overwrite()
    {
        this.overwrite = true;
        return this;
    }

    /**
     * Use data types which can not be extended later on. This may reduce the initial size of the
     * HDF5 file.
     */
    public HDF5Writer dontUseExtendableDataTypes()
    {
        this.useExtentableDataTypes = false;
        return this;
    }

    /**
     * Returns <code>true</code>, if the file was <em>not</em> configured with
     * {@link #dontUseExtendableDataTypes()}, that is if extendable data types are used for new data
     * sets.
     */
    public boolean isUseExtendableDataTypes()
    {
        return useExtentableDataTypes;
    }

    /**
     * A file will be created that uses the latest available file format. This may improve
     * performance or space consumption but in general means that older versions of the library are
     * no longer able to read this file.
     */
    public HDF5Writer useLatestFileFormat()
    {
        this.useLatestFileFormat = true;
        return this;
    }

    /**
     * Returns <code>true</code>, if the latest file format will be used and <code>false</code>, if
     * a file format with maximum compatibility will be used.
     */
    public boolean isUseLatestFileFormat()
    {
        return useLatestFileFormat;
    }

    @Override
    public HDF5Writer open()
    {
        final String path = hdf5File.getAbsolutePath();
        this.fileId = openOrCreateFile(path);
        readNamedDataTypes();
        this.booleanDataTypeId = openOrCreateBooleanDataType();
        this.typeVariantDataTypeId = openOrCreateTypeVariantDataType();
        this.variableLengthStringDataTypeId = openOrCreateVLStringType();

        return this;
    }

    private int openOrCreateFile(final String path)
    {
        if (hdf5File.exists() && overwrite == false)
        {
            return h5.openFileReadWrite(path, useLatestFileFormat, fileRegistry);
        } else
        {
            final File directory = hdf5File.getParentFile();
            if (directory.exists() == false)
            {
                throw new HDF5JavaException("Directory '" + directory.getPath()
                        + "' does not exist.");
            }
            return h5.createFile(path, useLatestFileFormat, fileRegistry);
        }
    }

    @Override
    protected int openOrCreateTypeVariantDataType()
    {
        int dataTypeId = getDataTypeId(HDF5Utils.TYPE_VARIANT_DATA_TYPE);
        if (dataTypeId < 0
                || h5.getNumberOfMembers(dataTypeId) < HDF5DataTypeVariant.values().length)
        {
            final String typeVariantPath = findFirstUnusedTypeVariantPath();
            dataTypeId = createTypeVariantDataType();
            commitDataType(typeVariantPath, dataTypeId);
            createOrUpdateSoftLink(typeVariantPath.substring(DATATYPE_GROUP.length() + 1),
                    TYPE_VARIANT_DATA_TYPE);
        }
        return dataTypeId;
    }

    private final static int MAX_TYPE_VARIANTS = 1024;

    private String findFirstUnusedTypeVariantPath()
    {
        int number = 0;
        String path;
        do
        {
            path = TYPE_VARIANT_DATA_TYPE + "." + (number++);
        } while (exists(path) && number < MAX_TYPE_VARIANTS);
        return path;
    }

    private int openOrCreateVLStringType()
    {
        int dataTypeId = getDataTypeId(HDF5Utils.VARIABLE_LENGTH_STRING_DATA_TYPE);
        if (dataTypeId < 0)
        {
            dataTypeId = h5.createDataTypeVariableString(fileRegistry);
            commitDataType(VARIABLE_LENGTH_STRING_DATA_TYPE, dataTypeId);
        }
        return dataTypeId;
    }

    // /////////////////////
    // File
    // /////////////////////

    /**
     * Flushes the file to disk (without discarding the cache).
     */
    public void flush()
    {
        checkOpen();
        h5.flushFile(fileId);
    }

    // /////////////////////
    // Objects & Links
    // /////////////////////

    /**
     * Creates a hard link.
     * 
     * @param currentPath The name of the data set (including path information) to create a link to.
     * @param newPath The name (including path information) of the link to create.
     */
    public void createHardLink(String currentPath, String newPath)
    {
        assert currentPath != null;
        assert newPath != null;

        checkOpen();
        h5.createHardLink(fileId, currentPath, newPath);
    }

    /**
     * Creates a soft link.
     * 
     * @param targetPath The name of the data set (including path information) to create a link to.
     * @param linkPath The name (including path information) of the link to create.
     */
    public void createSoftLink(String targetPath, String linkPath)
    {
        assert targetPath != null;
        assert linkPath != null;

        checkOpen();
        h5.createSoftLink(fileId, linkPath, targetPath);
    }

    /**
     * Creates or updates a soft link.
     * <p>
     * <em>Note: This method will never overwrite a data set, but only a symbolic link.</em>
     * 
     * @param targetPath The name of the data set (including path information) to create a link to.
     * @param linkPath The name (including path information) of the link to create.
     */
    public void createOrUpdateSoftLink(String targetPath, String linkPath)
    {
        assert targetPath != null;
        assert linkPath != null;

        checkOpen();
        if (isSymbolicLink(linkPath))
        {
            delete(linkPath);
        }
        h5.createSoftLink(fileId, linkPath, targetPath);
    }

    /**
     * Creates an external link, that is a link to a data set in another HDF5 file, the
     * <em>target</em> .
     * <p>
     * <em>Note: This method is only allowed when the file was configured with 
     * {@link #useLatestFileFormat()}.</em>
     * 
     * @param targetFileName The name of the file where the data set resides that should be linked.
     * @param targetPath The name of the data set (including path information) in the
     *            <var>targetFileName</var> to create a link to.
     * @param linkPath The name (including path information) of the link to create.
     * @throws IllegalStateException If the file was not configured with
     *             {@link #useLatestFileFormat()}.
     */
    public void createExternalLink(String targetFileName, String targetPath, String linkPath)
            throws IllegalStateException
    {
        assert targetFileName != null;
        assert targetPath != null;
        assert linkPath != null;

        checkOpen();
        if (useLatestFileFormat == false)
        {
            throw new IllegalStateException("External links are not allowed with HDF5 1.6.x files.");
        }
        h5.createExternalLink(fileId, linkPath, targetFileName, targetPath);
    }

    /**
     * Creates or updates an external link, that is a link to a data set in another HDF5 file, the
     * <em>target</em> .
     * <p>
     * <em>Note: This method will never overwrite a data set, but only a symbolic link.</em>
     * <p>
     * <em>Note: This method is only allowed when the file was configured with 
     * {@link #useLatestFileFormat()}.</em>
     * 
     * @param targetFileName The name of the file where the data set resides that should be linked.
     * @param targetPath The name of the data set (including path information) in the
     *            <var>targetFileName</var> to create a link to.
     * @param linkPath The name (including path information) of the link to create.
     * @throws IllegalStateException If the file was not configured with
     *             {@link #useLatestFileFormat()}.
     */
    public void createOrUpdateExternalLink(String targetFileName, String targetPath, String linkPath)
            throws IllegalStateException
    {
        assert targetFileName != null;
        assert targetPath != null;
        assert linkPath != null;

        checkOpen();
        if (useLatestFileFormat == false)
        {
            throw new IllegalStateException("External links are not allowed with HDF5 1.6.x files.");
        }
        if (isSymbolicLink(linkPath))
        {
            delete(linkPath);
        }
        h5.createExternalLink(fileId, linkPath, targetFileName, targetPath);
    }

    /**
     * Removes an object from the file. If there is more than one link to the object, only the
     * specified link will be removed.
     */
    public void delete(String objectPath)
    {
        checkOpen();
        if (isGroup(objectPath))
        {
            for (String path : getGroupMemberPaths(objectPath))
            {
                delete(path);
            }
        }
        h5.deleteObject(fileId, objectPath);
    }

    // /////////////////////
    // Group
    // /////////////////////

    /**
     * Creates a group with path <var>objectPath</var> in the HDF5 file.
     * <p>
     * All intermediate groups will be created as well, if they do not already exist.
     * 
     * @param groupPath The path of the group to create.
     */
    public void createGroup(final String groupPath)
    {
        checkOpen();
        h5.createGroup(fileId, groupPath);
    }

    /**
     * Creates a group with path <var>objectPath</var> in the HDF5 file, giving the library a hint
     * about the size (<var>sizeHint</var>). If you have this information in advance, it will be
     * more efficient to tell it the library rather than to let the library figure out itself, but
     * the hint must not be misunderstood as a limit.
     * <p>
     * All intermediate groups will be created as well, if they do not already exist.
     * <p>
     * <i>Note: This method creates an "old-style group", that is the type of group of HDF5 1.6 and
     * earlier.</i>
     * 
     * @param groupPath The path of the group to create.
     * @param sizeHint The estimated size of all group entries (in bytes).
     */
    public void createGroup(final String groupPath, final int sizeHint)
    {
        checkOpen();
        final ICallableWithCleanUp<Object> addAttributeRunnable =
                new ICallableWithCleanUp<Object>()
                    {
                        public Object call(ICleanUpRegistry registry)
                        {
                            h5.createOldStyleGroup(fileId, groupPath, sizeHint, registry);
                            return null; // Nothing to return.
                        }
                    };
        runner.call(addAttributeRunnable);
    }

    /**
     * Creates a group with path <var>objectPath</var> in the HDF5 file, giving the library hints
     * about when to switch between compact and dense. Setting appropriate values may improve
     * performance.
     * <p>
     * All intermediate groups will be created as well, if they do not already exist.
     * <p>
     * <i>Note: This method creates a "new-style group", that is the type of group of HDF5 1.8 and
     * above. Thus it will fail, if you didn't configure the file to be
     * {@link #useLatestFileFormat()}.</i>
     * 
     * @param groupPath The path of the group to create.
     * @param maxCompact When the group grows to more than this number of entries, the library will
     *            convert the group style from compact to dense.
     * @param minDense When the group shrinks below this number of entries, the library will convert
     *            the group style from dense to compact.
     */
    public void createGroup(final String groupPath, final int maxCompact, final int minDense)
    {
        checkOpen();
        final ICallableWithCleanUp<Object> addAttributeRunnable =
                new ICallableWithCleanUp<Object>()
                    {
                        public Object call(ICleanUpRegistry registry)
                        {
                            h5.createNewStyleGroup(fileId, groupPath, maxCompact, minDense,
                                    registry);
                            return null; // Nothing to return.
                        }
                    };
        runner.call(addAttributeRunnable);
    }

    // /////////////////////
    // Attributes
    // /////////////////////

    /**
     * Deletes an attribute.
     * <p>
     * The referenced object must exist, that is it need to have been written before by one of the
     * <code>write()</code> methods.
     * 
     * @param objectPath The name of the object to delete the attribute from.
     * @param name The name of the attribute to delete.
     */
    public void deleteAttribute(final String objectPath, final String name)
    {
        checkOpen();
        final ICallableWithCleanUp<Object> addAttributeRunnable =
                new ICallableWithCleanUp<Object>()
                    {
                        public Object call(ICleanUpRegistry registry)
                        {
                            final int objectId = h5.openObject(fileId, objectPath, registry);
                            h5.deleteAttribute(objectId, name);
                            return null; // Nothing to return.
                        }
                    };
        runner.call(addAttributeRunnable);
    }

    /**
     * Adds an enum attribute to the referenced object.
     * <p>
     * The referenced object must exist, that is it need to have been written before by one of the
     * <code>write()</code> methods.
     * 
     * @param objectPath The name of the object to add the attribute to.
     * @param name The name of the attribute.
     * @param value The value of the attribute.
     */
    public void addEnumAttribute(final String objectPath, final String name,
            final HDF5EnumerationValue value)
    {
        assert objectPath != null;
        assert name != null;
        assert value != null;

        checkOpen();
        value.getType().check(fileId);
        final int nativeDataTypeId = value.getType().getNativeTypeId();
        addAttribute(objectPath, name, nativeDataTypeId, value.toStorageForm());
    }

    /**
     * Adds an attribute for the <var>typeVariant</var> of <var>objectPath</var>.
     */
    private void addTypeVariantAttribute(final String objectPath,
            final HDF5DataTypeVariant typeVariant)
    {
        checkOpen();
        addAttribute(objectPath, TYPE_VARIANT_ATTRIBUTE, typeVariantDataTypeId, new int[]
            { typeVariant.ordinal() });
    }

    /**
     * Adds a string attribute to the referenced object.
     * <p>
     * The referenced object must exist, that is it need to have been written before by one of the
     * <code>write()</code> methods.
     * 
     * @param objectPath The name of the object to add the attribute to.
     * @param name The name of the attribute.
     * @param value The value of the attribute.
     */
    public void addStringAttribute(final String objectPath, final String name, final String value)
    {
        addStringAttribute(objectPath, name, value, value.length());
    }

    /**
     * Adds a string attribute to the referenced object.
     * <p>
     * The referenced object must exist, that is it need to have been written before by one of the
     * <code>write()</code> methods.
     * 
     * @param objectPath The name of the object to add the attribute to.
     * @param name The name of the attribute.
     * @param value The value of the attribute.
     * @param maxLength The maximal length of the value.
     */
    public void addStringAttribute(final String objectPath, final String name, final String value,
            final int maxLength)
    {
        assert name != null;
        assert value != null;

        checkOpen();
        final ICallableWithCleanUp<Object> addAttributeRunnable =
                new ICallableWithCleanUp<Object>()
                    {
                        public Object call(ICleanUpRegistry registry)
                        {
                            final int objectId = h5.openObject(fileId, objectPath, registry);
                            final int stringDataTypeId =
                                    h5.createDataTypeString(maxLength + 1, registry);
                            final int attributeId;
                            if (h5.existsAttribute(objectId, name))
                            {
                                attributeId = h5.openAttribute(objectId, name, registry);
                            } else
                            {
                                attributeId =
                                        h5.createAttribute(objectId, name, stringDataTypeId,
                                                registry);
                            }
                            h5.writeAttribute(attributeId, stringDataTypeId, (value + '\0')
                                    .getBytes());
                            return null; // Nothing to return.
                        }
                    };
        runner.call(addAttributeRunnable);
    }

    /**
     * Adds a <code>boolean</code> attribute to the referenced object.
     * <p>
     * The referenced object must exist, that is it need to have been written before by one of the
     * <code>write()</code> methods.
     * 
     * @param objectPath The name of the object to add the attribute to.
     * @param name The name of the attribute.
     * @param value The value of the attribute.
     */
    public void addBooleanAttribute(final String objectPath, final String name, final boolean value)
    {
        checkOpen();
        addAttribute(objectPath, name, getDataTypeId(BOOLEAN_DATA_TYPE), new byte[]
            { (byte) (value ? 1 : 0) });
    }

    /**
     * Adds an <code>int</code> attribute to the referenced object.
     * <p>
     * The referenced object must exist, that is it need to have been written before by one of the
     * <code>write()</code> methods.
     * 
     * @param objectPath The name of the object to add the attribute to.
     * @param name The name of the attribute.
     * @param value The value of the attribute.
     */
    public void addIntAttribute(final String objectPath, final String name, final int value)
    {
        checkOpen();
        addAttribute(objectPath, name, H5T_STD_I32LE, HDFNativeData.intToByte(value));
    }

    /**
     * Adds a long attribute to the referenced object.
     * <p>
     * The referenced object must exist, that is it need to have been written before by one of the
     * <code>write()</code> methods.
     * 
     * @param objectPath The name of the object to add the attribute to.
     * @param name The name of the attribute.
     * @param value The value of the attribute.
     */
    public void addLongAttribute(final String objectPath, final String name, final long value)
    {
        checkOpen();
        addAttribute(objectPath, name, H5T_STD_I64LE, HDFNativeData.longToByte(value));
    }

    /**
     * Adds a <code>float</code> attribute to the referenced object.
     * <p>
     * The referenced object must exist, that is it need to have been written before by one of the
     * <code>write()</code> methods.
     * 
     * @param objectPath The name of the object to add the attribute to.
     * @param name The name of the attribute.
     * @param value The value of the attribute.
     */
    public void addFloatAttribute(final String objectPath, final String name, final float value)
    {
        checkOpen();
        addAttribute(objectPath, name, H5T_IEEE_F32LE, HDFNativeData.floatToByte(value));
    }

    /**
     * Adds a <code>double</code> attribute to the referenced object.
     * <p>
     * The referenced object must exist, that is it need to have been written before by one of the
     * <code>write()</code> methods.
     * 
     * @param objectPath The name of the object to add the attribute to.
     * @param name The name of the attribute.
     * @param value The value of the attribute.
     */
    public void addDoubleAttribute(final String objectPath, final String name, final double value)
    {
        checkOpen();
        addAttribute(objectPath, name, H5T_IEEE_F64LE, HDFNativeData.doubleToByte(value));
    }

    private void addAttribute(final String objectPath, final String name,
            final int nativeDataTypeId, final Object value)
    {
        assert objectPath != null;
        assert name != null;
        assert nativeDataTypeId >= 0;
        assert value != null;

        final ICallableWithCleanUp<Object> addAttributeRunnable =
                new ICallableWithCleanUp<Object>()
                    {
                        public Object call(ICleanUpRegistry registry)
                        {
                            final int objectId = h5.openObject(fileId, objectPath, registry);
                            final int attributeId;
                            if (h5.existsAttribute(objectId, name))
                            {
                                attributeId = h5.openAttribute(objectId, name, registry);
                            } else
                            {
                                attributeId =
                                        h5.createAttribute(objectId, name, nativeDataTypeId,
                                                registry);
                            }
                            h5.writeAttribute(attributeId, nativeDataTypeId, value);
                            return null; // Nothing to return.
                        }
                    };
        runner.call(addAttributeRunnable);
    }

    // /////////////////////
    // Data Sets
    // /////////////////////

    //
    // Boolean
    //

    /**
     * Writes out a <code>boolean</code> value.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param value The value of the data set.
     */
    public void writeBoolean(final String objectPath, final boolean value)
    {
        checkOpen();
        final int dataTypeId = getDataTypeId(BOOLEAN_DATA_TYPE);
        writeScalar(objectPath, dataTypeId, dataTypeId, HDFNativeData.byteToByte((byte) (value ? 1
                : 0)));
    }

    /**
     * Writes out a bit field ((which can be considered the equivalent to a boolean array of rank
     * 1), provided as a Java {@link BitSet}. Uses a compact storage layout. Must only be used for
     * small data sets.
     * <p>
     * Note that the storage form of the bit array is a <code>long[]</code>. However, it is marked
     * in HDF5 to be interpreted bit-wise. Thus a data set written by this method cannot be read
     * back by {@link #readLongArray(String)} but will throw a
     * {@link ncsa.hdf.hdf5lib.exceptions.HDF5DatatypeInterfaceException}.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     */
    public void writeBitFieldCompact(final String objectPath, final BitSet data)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        final long[] longArray = BitSetConversionUtils.toStorageForm(data);
        final int msb = data.length();
        final int realLength = msb / 64 + (msb % 64 != 0 ? 1 : 0);
        writeRank1Compact(objectPath, H5T_STD_B64LE, H5T_NATIVE_B64, longArray, realLength);
    }

    /**
     * Writes out a bit field ((which can be considered the equivalent to a boolean array of rank
     * 1), provided as a Java {@link BitSet}.
     * <p>
     * Note that the storage form of the bit array is a <code>long[]</code>. However, it is marked
     * in HDF5 to be interpreted bit-wise. Thus a data set written by this method cannot be read
     * back by {@link #readLongArray(String)} but will throw a
     * {@link ncsa.hdf.hdf5lib.exceptions.HDF5DatatypeInterfaceException}.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     */
    public void writeBitField(final String objectPath, final BitSet data)
    {
        writeBitField(objectPath, data, false);
    }

    /**
     * Writes out a bit field ((which can be considered the equivalent to a boolean array of rank
     * 1), provided as a Java {@link BitSet}.
     * <p>
     * Note that the storage form of the bit array is a <code>long[]</code>. However, it is marked
     * in HDF5 to be interpreted bit-wise. Thus a data set written by this method cannot be read
     * back by {@link #readLongArray(String)} but will throw a
     * {@link ncsa.hdf.hdf5lib.exceptions.HDF5DatatypeInterfaceException}.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void writeBitField(final String objectPath, final BitSet data, boolean deflate)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        final long[] longArray = BitSetConversionUtils.toStorageForm(data);
        final int msb = data.length();
        final int realLength = msb / 64 + (msb % 64 != 0 ? 1 : 0);
        writeRank1(objectPath, H5T_STD_B64LE, H5T_NATIVE_B64, getDeflateLevel(deflate), longArray,
                realLength);
    }

    //
    // Opaque
    //

    /**
     * Writes out an opaque data type described by <var>tag</var> and defined by a <code>byte</code>
     * array (of rank 1). Uses a compact storage layout. Must only be used for small data sets.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param tag The tag of the data set.
     * @param data The data to write. Must not be <code>null</code>.
     */
    public void writeOpaqueByteArrayCompact(final String objectPath, final String tag,
            final byte[] data)
    {
        assert objectPath != null;
        assert tag != null;
        assert data != null;

        checkOpen();
        final int dataTypeId = getOrCreateOpaqueTypeId(tag);
        writeRank1Compact(objectPath, dataTypeId, dataTypeId, data, data.length);
    }

    /**
     * Writes out an opaque data type described by <var>tag</var> and defined by a <code>byte</code>
     * array (of rank 1).
     * <p>
     * Note that there is no dedicated method for reading opaque types. Use the method
     * {@link #readAsByteArray(String)} instead.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param tag The tag of the data set.
     * @param data The data to write. Must not be <code>null</code>.
     */
    public void writeOpaqueByteArray(final String objectPath, final String tag, final byte[] data)
    {
        writeOpaqueByteArray(objectPath, tag, data, false);
    }

    /**
     * Writes out an opaque data type described by <var>tag</var> and defined by a <code>byte</code>
     * array (of rank 1).
     * <p>
     * Note that there is no dedicated method for reading opaque types. Use the method
     * {@link #readAsByteArray(String)} instead.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param tag The tag of the data set.
     * @param data The data to write. Must not be <code>null</code>.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void writeOpaqueByteArray(final String objectPath, final String tag, final byte[] data,
            boolean deflate)
    {
        assert objectPath != null;
        assert tag != null;
        assert data != null;

        checkOpen();
        final int dataTypeId = getOrCreateOpaqueTypeId(tag);
        writeRank1(objectPath, dataTypeId, dataTypeId, getDeflateLevel(deflate), data, data.length);
    }

    /**
     * Creates an opaque data set that will be represented as a <code>byte</code> array (of rank 1).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param size The size of the byte vector to create.
     * @param blockSize The size of on block (for block-wise IO)
     * @return The {@link HDF5OpaqueType} that can be used in methods
     *         {@link #writeOpaqueByteArrayBlock(String, HDF5OpaqueType, byte[], long)} and
     *         {@link #writeOpaqueByteArrayBlockWithOffset(String, HDF5OpaqueType, byte[], int, long)}
     *         to represent this opaque type.
     */
    public HDF5OpaqueType createOpaqueByteArray(final String objectPath, final String tag,
            final long size, final int blockSize)
    {
        return createOpaqueByteArray(objectPath, tag, size, blockSize, false);
    }

    /**
     * Creates an opaque data set that will be represented as a <code>byte</code> array (of rank 1).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param size The size of the byte vector to create.
     * @param blockSize The size of on block (for block-wise IO)
     * @param deflate If <code>true</code>, the data set will be compressed.
     * @return The {@link HDF5OpaqueType} that can be used in methods
     *         {@link #writeOpaqueByteArrayBlock(String, HDF5OpaqueType, byte[], long)} and
     *         {@link #writeOpaqueByteArrayBlockWithOffset(String, HDF5OpaqueType, byte[], int, long)}
     *         to represent this opaque type.
     */
    public HDF5OpaqueType createOpaqueByteArray(final String objectPath, final String tag,
            final long size, final int blockSize, boolean deflate)
    {
        assert objectPath != null;
        assert tag != null;
        assert size >= 0;
        assert blockSize >= 0 && blockSize <= size;

        checkOpen();
        final int dataTypeId = getOrCreateOpaqueTypeId(tag);
        writeBlockRank1(objectPath, dataTypeId, dataTypeId, getDeflateLevel(deflate), null, size,
                blockSize, 0);
        return new HDF5OpaqueType(fileId, dataTypeId, tag);
    }

    /**
     * Writes out a block of an opaque data type represented by a <code>byte</code> array (of rank
     * 1). The data set needs to have been created by
     * {@link #createOpaqueByteArray(String, String, long, int, boolean)} beforehand.
     * <p>
     * <i>Note:</i> For best performance, the block size in this method should be chosen to be equal
     * to the <var>blockSize</var> argument of the
     * {@link #createOpaqueByteArray(String, String, long, int, boolean)} call that was used to
     * created the data set.
     * <p>
     * Note that there is no dedicated method for reading opaque types. Use the method
     * {@link #readAsByteArrayBlock(String, int, int)} instead.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. The length defines the block size. Must not be
     *            <code>null</code> or of length 0.
     * @param blockNumber The number of the block to write.
     */
    public void writeOpaqueByteArrayBlock(final String objectPath, final HDF5OpaqueType dataType,
            final byte[] data, final long blockNumber)
    {
        assert objectPath != null;
        assert dataType != null;
        assert data != null;

        checkOpen();
        dataType.check(fileId);
        writeBlockRank1(objectPath, dataType.getStorageTypeId(), dataType.getNativeTypeId(),
                HDF5.NO_DEFLATION, data, data.length, data.length, data.length * blockNumber);
    }

    /**
     * Writes out a block of an opaque data type represented by a <code>byte</code> array (of rank
     * 1). The data set needs to have been created by
     * {@link #createOpaqueByteArray(String, String, long, int, boolean)} beforehand.
     * <p>
     * Use this method instead of
     * {@link #writeOpaqueByteArrayBlock(String, HDF5OpaqueType, byte[], long)} if the total size of
     * the data set is not a multiple of the block size.
     * <p>
     * <i>Note:</i> For best performance, the typical <var>dataSize</var> in this method should be
     * chosen to be equal to the <var>blockSize</var> argument of the
     * {@link #createOpaqueByteArray(String, String, long, int, boolean)} call that was used to
     * created the data set.
     * <p>
     * Note that there is no dedicated method for reading opaque types. Use the method
     * {@link #readAsByteArrayBlockWithOffset(String, int, int)} instead.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. The length defines the block size. Must not be
     *            <code>null</code> or of length 0.
     * @param dataSize The (real) size of <code>data</code> (needs to be <code><= data.length</code>
     *            )
     * @param offset The offset in the data set to start writing to.
     */
    public void writeOpaqueByteArrayBlockWithOffset(final String objectPath,
            final HDF5OpaqueType dataType, final byte[] data, final int dataSize, final long offset)
    {
        assert objectPath != null;
        assert dataType != null;
        assert data != null;

        checkOpen();
        dataType.check(fileId);
        writeBlockRank1(objectPath, dataType.getStorageTypeId(), dataType.getNativeTypeId(),
                HDF5.NO_DEFLATION, data, dataSize, dataSize, offset);
    }

    private int getOrCreateOpaqueTypeId(final String tag)
    {
        final String dataTypePath = createDataTypePath(OPAQUE_PREFIX, tag);
        int dataTypeId = getDataTypeId(dataTypePath);
        if (dataTypeId < 0)
        {
            dataTypeId = h5.createDataTypeOpaque(1, tag, fileRegistry);
            commitDataType(dataTypePath, dataTypeId);
        }
        return dataTypeId;
    }

    // ------------------------------------------------------------------------------
    // GENERATED CODE SECTION - START
    // ------------------------------------------------------------------------------

    //
    // Byte
    //

    /**
     * Writes out a <code>byte</code> value.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param value The value to write.
     */
    public void writeByte(final String objectPath, final byte value)
    {
        assert objectPath != null;

        checkOpen();
        writeScalar(objectPath, H5T_STD_I8LE, H5T_NATIVE_INT8, HDFNativeData.byteToByte(value));
    }

    /**
     * Creates a <code>byte</code> array (of rank 1). Uses a compact storage layout. Should only be
     * used for small data sets.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param length The length of the data set to create.
     */
    public void createByteArrayCompact(final String objectPath, final long length)
    {
        assert objectPath != null;
        assert length > 0;

        checkOpen();
        writeRank1Compact(objectPath, H5T_STD_I8LE, H5T_NATIVE_INT8, null, length);
    }

    /**
     * Writes out a <code>byte</code> array (of rank 1). Uses a compact storage layout. Should only
     * be used for small data sets.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     */
    public void writeByteArrayCompact(final String objectPath, final byte[] data)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        writeRank1Compact(objectPath, H5T_STD_I8LE, H5T_NATIVE_INT8, data, data.length);
    }

    /**
     * Writes out a <code>byte</code> array (of rank 1).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     */
    public void writeByteArray(final String objectPath, final byte[] data)
    {
        writeByteArray(objectPath, data, false);
    }

    /**
     * Writes out a <code>byte</code> array (of rank 1).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void writeByteArray(final String objectPath, final byte[] data, boolean deflate)
    {
        assert data != null;

        checkOpen();
        writeRank1(objectPath, H5T_STD_I8LE, H5T_NATIVE_INT8, getDeflateLevel(deflate), data,
                data.length);
    }

    /**
     * Creates a <code>byte</code> array (of rank 1).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param size The size of the byte vector to create. When using extendable data sets ((see
     *            {@link #dontUseExtendableDataTypes()})), then no data set smaller than this size
     *            can be created, however data sets may be larger.
     * @param blockSize The size of one block (for block-wise IO). Ignored if no extendable data
     *            sets are used (see {@link #dontUseExtendableDataTypes()}).
     */
    public void createByteArray(final String objectPath, final long size, final int blockSize)
    {
        assert objectPath != null;
        assert size >= 0;
        assert blockSize >= 0 && blockSize <= size;

        checkOpen();
        createByteArray(objectPath, size, blockSize, false);
    }

    /**
     * Creates a <code>byte</code> array (of rank 1).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param size The size of the byte array to create. When using extendable data sets ((see
     *            {@link #dontUseExtendableDataTypes()})), then no data set smaller than this size
     *            can be created, however data sets may be larger.
     * @param blockSize The size of one block (for block-wise IO). Ignored if no extendable data
     *            sets are used (see {@link #dontUseExtendableDataTypes()}) and
     *            <code>deflate == false</code>.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void createByteArray(final String objectPath, final long size, final int blockSize,
            boolean deflate)
    {
        assert objectPath != null;
        assert size >= 0;
        assert blockSize >= 0 && blockSize <= size;

        checkOpen();
        writeBlockRank1(objectPath, H5T_STD_I8LE, H5T_NATIVE_INT8, getDeflateLevel(deflate), null,
                size, blockSize, 0);
    }

    /**
     * Writes out a block of a <code>byte</code> array (of rank 1). The data set needs to have been
     * created by {@link #createByteArray(String, long, int, boolean)} beforehand.
     * <p>
     * <i>Note:</i> For best performance, the block size in this method should be chosen to be equal
     * to the <var>blockSize</var> argument of the
     * {@link #createByteArray(String, long, int, boolean)} call that was used to create the data
     * set.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. The length defines the block size. Must not be
     *            <code>null</code> or of length 0.
     * @param blockNumber The number of the block to write.
     */
    public void writeByteArrayBlock(final String objectPath, final byte[] data,
            final long blockNumber)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        writeBlockRank1(objectPath, H5T_STD_I8LE, H5T_NATIVE_INT8, HDF5.NO_DEFLATION, data,
                data.length, data.length, data.length * blockNumber);
    }

    /**
     * Writes out a block of a <code>byte</code> array (of rank 1). The data set needs to have been
     * created by {@link #createByteArray(String, long, int, boolean)} beforehand.
     * <p>
     * Use this method instead of {@link #writeByteArrayBlock(String, byte[], long)} if the total
     * size of the data set is not a multiple of the block size.
     * <p>
     * <i>Note:</i> For best performance, the typical <var>dataSize</var> in this method should be
     * chosen to be equal to the <var>blockSize</var> argument of the
     * {@link #createByteArray(String, long, int, boolean)} call that was used to create the data
     * set.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. The length defines the block size. Must not be
     *            <code>null</code> or of length 0.
     * @param dataSize The (real) size of <code>data</code> (needs to be <code><= data.length</code>
     *            )
     * @param offset The offset in the data set to start writing to.
     */
    public void writeByteArrayBlockWithOffset(final String objectPath, final byte[] data,
            final int dataSize, final long offset)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        writeBlockRank1(objectPath, H5T_STD_I8LE, H5T_NATIVE_INT8, HDF5.NO_DEFLATION, data,
                dataSize, dataSize, offset);
    }

    /**
     * Writes out a <code>byte</code> matrix (array of rank 2).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     */
    public void writeByteMatrix(final String objectPath, final byte[][] data)
    {
        writeByteMatrix(objectPath, data, false);
    }

    /**
     * Writes out a <code>byte</code> matrix (array of rank 2).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void writeByteMatrix(final String objectPath, final byte[][] data, boolean deflate)
    {
        assert data != null;
        assert checkDimensions(data);

        checkOpen();
        writeRankN(objectPath, H5T_STD_I8LE, H5T_NATIVE_INT8, getDeflateLevel(deflate), data,
                new long[]
                    { data.length, data.length == 0 ? 0 : data[0].length });
    }

    /**
     * Creates a <code>byte</code> matrix (array of rank 2).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param sizeX The size of the x dimension of the byte matrix to create.
     * @param sizeY The size of the y dimension of the byte matrix to create.
     * @param blockSizeX The size of one block in the x dimension.
     * @param blockSizeY The size of one block in the y dimension.
     */
    public void createByteMatrix(final String objectPath, final long sizeX, final long sizeY,
            int blockSizeX, int blockSizeY)
    {
        createByteMatrix(objectPath, sizeX, sizeY, blockSizeX, blockSizeY, false);
    }

    /**
     * Creates a <code>byte</code> matrix (array of rank 2).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param sizeX The size of the x dimension of the byte matrix to create.
     * @param sizeY The size of the y dimension of the byte matrix to create.
     * @param blockSizeX The size of one block in the x dimension.
     * @param blockSizeY The size of one block in the y dimension.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void createByteMatrix(final String objectPath, final long sizeX, final long sizeY,
            int blockSizeX, int blockSizeY, boolean deflate)
    {
        assert objectPath != null;
        assert sizeX >= 0;
        assert sizeY >= 0;
        assert blockSizeX >= 0 && blockSizeX <= sizeX;
        assert blockSizeY >= 0 && blockSizeY <= sizeY;

        checkOpen();
        writeBlockRankN(objectPath, H5T_STD_I8LE, H5T_NATIVE_INT8, getDeflateLevel(deflate), null,
                new long[]
                    { sizeX, sizeY }, new long[]
                    { blockSizeX, blockSizeY }, new long[]
                    { 0, 0 });
    }

    /**
     * Writes out a block of a <code>byte</code> matrix (array of rank 2). The data set needs to
     * have been created by {@link #createByteMatrix(String, long, long, int, int, boolean)}
     * beforehand.
     * <p>
     * Use this method instead of {@link #createByteMatrix(String, long, long, int, int, boolean)}
     * if the total size of the data set is not a multiple of the block size.
     * <p>
     * <i>Note:</i> For best performance, the size of <var>data</var> in this method should match
     * the <var>blockSizeX/Y</var> arguments of the
     * {@link #createByteMatrix(String, long, long, int, int, boolean)} call that was used to create
     * the data set.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. The length defines the block size. Must not be
     *            <code>null</code> or of length 0.
     * @param blockNumberX The block number in the x dimension (offset: multiply with
     *            <code>data.length</code>).
     * @param blockNumberY The block number in the y dimension (offset: multiply with
     *            <code>data[0.length</code>).
     */
    public void writeByteMatrixBlock(final String objectPath, final byte[][] data,
            final long blockNumberX, final long blockNumberY)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        final long[] blockDimensions = new long[]
            { data.length, data[0].length };
        writeBlockRankN(objectPath, H5T_STD_I8LE, H5T_NATIVE_INT8, HDF5.NO_DEFLATION, data,
                blockDimensions, blockDimensions, new long[]
                    { blockNumberX * data.length, blockNumberY * data[0].length });
    }

    /**
     * Writes out a block of a <code>byte</code> matrix (array of rank 2). The data set needs to
     * have been created by {@link #createByteMatrix(String, long, long, int, int, boolean)}
     * beforehand.
     * <p>
     * Use this method instead of {@link #writeByteMatrixBlock(String, byte[][], long, long)} if the
     * total size of the data set is not a multiple of the block size.
     * <p>
     * <i>Note:</i> For best performance, the typical <var>dataSize</var> in this method should be
     * chosen to be equal to the <var>blockSize</var> argument of the
     * {@link #createByteMatrix(String, long, long, int, int, boolean)} call that was used to create
     * the data set.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write.
     * @param offsetX The x offset in the data set to start writing to.
     * @param offsetY The y offset in the data set to start writing to.
     */
    public void writeByteMatrixBlockWithOffset(final String objectPath, final byte[][] data,
            final long offsetX, final long offsetY)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        final long[] blockDimensions = new long[]
            { data.length, data[0].length };
        writeBlockRankN(objectPath, H5T_STD_I8LE, H5T_NATIVE_INT8, HDF5.NO_DEFLATION, data,
                blockDimensions, blockDimensions, new long[]
                    { offsetX, offsetY });
    }

    /**
     * Writes out a multi-dimensional <code>byte</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     */
    public void writeByteMDArray(final String objectPath, final MDByteArray data)
    {
        writeByteMDArray(objectPath, data, false);
    }

    /**
     * Writes out a multi-dimensional <code>byte</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void writeByteMDArray(final String objectPath, final MDByteArray data,
            final boolean deflate)
    {
        assert data != null;

        checkOpen();
        writeRankN(objectPath, H5T_STD_I8LE, H5T_NATIVE_INT8, getDeflateLevel(deflate), data
                .getAsFlatArray(), data.longDimensions());
    }

    /**
     * Creates a multi-dimensional <code>byte</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param dimensions The dimensions of the array.
     * @param blockDimensions The dimensions of one block (chunk) of the array.
     */
    public void createByteMDArray(final String objectPath, final long[] dimensions,
            final int[] blockDimensions)
    {
        createByteMDArray(objectPath, dimensions, blockDimensions, false);
    }

    /**
     * Creates a multi-dimensional <code>byte</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param dimensions The dimensions of the array.
     * @param blockDimensions The dimensions of one block (chunk) of the array.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void createByteMDArray(final String objectPath, final long[] dimensions,
            final int[] blockDimensions, final boolean deflate)
    {
        assert objectPath != null;
        assert dimensions != null;
        assert blockDimensions != null;

        checkOpen();
        writeBlockRankN(objectPath, H5T_STD_I8LE, H5T_NATIVE_INT8, getDeflateLevel(deflate), null,
                dimensions, MDArray.toLong(blockDimensions), null);
    }

    /**
     * Writes out a block of a multi-dimensional <code>byte</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     * @param blockNumber The block number in each dimension (offset: multiply with the extend in
     *            the according dimension).
     */
    public void writeByteMDArrayBlock(final String objectPath, final MDByteArray data,
            final long[] blockNumber)
    {
        assert data != null;
        assert blockNumber != null;

        checkOpen();
        final long[] dimensions = data.longDimensions();
        assert dimensions.length == blockNumber.length;
        final long[] offset = new long[dimensions.length];
        for (int i = 0; i < offset.length; ++i)
        {
            offset[i] = blockNumber[i] * dimensions[i];
        }
        writeBlockRankN(objectPath, H5T_STD_I8LE, H5T_NATIVE_INT8, HDF5.NO_DEFLATION, data
                .getAsFlatArray(), dimensions, dimensions, offset);
    }

    /**
     * Writes out a block of a multi-dimensional <code>byte</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     * @param offset The offset in the data set to start writing to in each dimension.
     */
    public void writeByteMDArrayBlockWithOffset(final String objectPath, final MDByteArray data,
            final long[] offset)
    {
        assert data != null;
        assert offset != null;

        checkOpen();
        final long[] dimensions = data.longDimensions();
        assert dimensions.length == offset.length;
        writeBlockRankN(objectPath, H5T_STD_I8LE, H5T_NATIVE_INT8, HDF5.NO_DEFLATION, data
                .getAsFlatArray(), dimensions, dimensions, offset);
    }

    //
    // Short
    //

    /**
     * Writes out a <code>short</code> value.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param value The value to write.
     */
    public void writeShort(final String objectPath, final short value)
    {
        assert objectPath != null;

        checkOpen();
        writeScalar(objectPath, H5T_STD_I16LE, H5T_NATIVE_INT16, HDFNativeData.shortToByte(value));
    }

    /**
     * Creates a <code>short</code> array (of rank 1). Uses a compact storage layout. Should only be
     * used for small data sets.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param length The length of the data set to create.
     */
    public void createShortArrayCompact(final String objectPath, final long length)
    {
        assert objectPath != null;
        assert length > 0;

        checkOpen();
        writeRank1Compact(objectPath, H5T_STD_I16LE, H5T_NATIVE_INT16, null, length);
    }

    /**
     * Writes out a <code>short</code> array (of rank 1). Uses a compact storage layout. Should only
     * be used for small data sets.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     */
    public void writeShortArrayCompact(final String objectPath, final short[] data)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        writeRank1Compact(objectPath, H5T_STD_I16LE, H5T_NATIVE_INT16, data, data.length);
    }

    /**
     * Writes out a <code>short</code> array (of rank 1).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     */
    public void writeShortArray(final String objectPath, final short[] data)
    {
        writeShortArray(objectPath, data, false);
    }

    /**
     * Writes out a <code>short</code> array (of rank 1).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void writeShortArray(final String objectPath, final short[] data, boolean deflate)
    {
        assert data != null;

        checkOpen();
        writeRank1(objectPath, H5T_STD_I16LE, H5T_NATIVE_INT16, getDeflateLevel(deflate), data,
                data.length);
    }

    /**
     * Creates a <code>short</code> array (of rank 1).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param size The size of the short vector to create. When using extendable data sets ((see
     *            {@link #dontUseExtendableDataTypes()})), then no data set smaller than this size
     *            can be created, however data sets may be larger.
     * @param blockSize The size of one block (for block-wise IO). Ignored if no extendable data
     *            sets are used (see {@link #dontUseExtendableDataTypes()}).
     */
    public void createShortArray(final String objectPath, final long size, final int blockSize)
    {
        assert objectPath != null;
        assert size >= 0;
        assert blockSize >= 0 && blockSize <= size;

        checkOpen();
        createShortArray(objectPath, size, blockSize, false);
    }

    /**
     * Creates a <code>short</code> array (of rank 1).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param size The size of the short array to create. When using extendable data sets ((see
     *            {@link #dontUseExtendableDataTypes()})), then no data set smaller than this size
     *            can be created, however data sets may be larger.
     * @param blockSize The size of one block (for block-wise IO). Ignored if no extendable data
     *            sets are used (see {@link #dontUseExtendableDataTypes()}) and
     *            <code>deflate == false</code>.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void createShortArray(final String objectPath, final long size, final int blockSize,
            boolean deflate)
    {
        assert objectPath != null;
        assert size >= 0;
        assert blockSize >= 0 && blockSize <= size;

        checkOpen();
        writeBlockRank1(objectPath, H5T_STD_I16LE, H5T_NATIVE_INT16, getDeflateLevel(deflate),
                null, size, blockSize, 0);
    }

    /**
     * Writes out a block of a <code>short</code> array (of rank 1). The data set needs to have been
     * created by {@link #createShortArray(String, long, int, boolean)} beforehand.
     * <p>
     * <i>Note:</i> For best performance, the block size in this method should be chosen to be equal
     * to the <var>blockSize</var> argument of the
     * {@link #createShortArray(String, long, int, boolean)} call that was used to create the data
     * set.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. The length defines the block size. Must not be
     *            <code>null</code> or of length 0.
     * @param blockNumber The number of the block to write.
     */
    public void writeShortArrayBlock(final String objectPath, final short[] data,
            final long blockNumber)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        writeBlockRank1(objectPath, H5T_STD_I16LE, H5T_NATIVE_INT16, HDF5.NO_DEFLATION, data,
                data.length, data.length, data.length * blockNumber);
    }

    /**
     * Writes out a block of a <code>short</code> array (of rank 1). The data set needs to have been
     * created by {@link #createShortArray(String, long, int, boolean)} beforehand.
     * <p>
     * Use this method instead of {@link #writeShortArrayBlock(String, short[], long)} if the total
     * size of the data set is not a multiple of the block size.
     * <p>
     * <i>Note:</i> For best performance, the typical <var>dataSize</var> in this method should be
     * chosen to be equal to the <var>blockSize</var> argument of the
     * {@link #createShortArray(String, long, int, boolean)} call that was used to create the data
     * set.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. The length defines the block size. Must not be
     *            <code>null</code> or of length 0.
     * @param dataSize The (real) size of <code>data</code> (needs to be <code><= data.length</code>
     *            )
     * @param offset The offset in the data set to start writing to.
     */
    public void writeShortArrayBlockWithOffset(final String objectPath, final short[] data,
            final int dataSize, final long offset)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        writeBlockRank1(objectPath, H5T_STD_I16LE, H5T_NATIVE_INT16, HDF5.NO_DEFLATION, data,
                dataSize, dataSize, offset);
    }

    /**
     * Writes out a <code>short</code> matrix (array of rank 2).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     */
    public void writeShortMatrix(final String objectPath, final short[][] data)
    {
        writeShortMatrix(objectPath, data, false);
    }

    /**
     * Writes out a <code>short</code> matrix (array of rank 2).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void writeShortMatrix(final String objectPath, final short[][] data, boolean deflate)
    {
        assert data != null;
        assert checkDimensions(data);

        checkOpen();
        writeRankN(objectPath, H5T_STD_I16LE, H5T_NATIVE_INT16, getDeflateLevel(deflate), data,
                new long[]
                    { data.length, data.length == 0 ? 0 : data[0].length });
    }

    /**
     * Creates a <code>short</code> matrix (array of rank 2).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param sizeX The size of the x dimension of the short matrix to create.
     * @param sizeY The size of the y dimension of the short matrix to create.
     * @param blockSizeX The size of one block in the x dimension.
     * @param blockSizeY The size of one block in the y dimension.
     */
    public void createShortMatrix(final String objectPath, final long sizeX, final long sizeY,
            int blockSizeX, int blockSizeY)
    {
        createShortMatrix(objectPath, sizeX, sizeY, blockSizeX, blockSizeY, false);
    }

    /**
     * Creates a <code>short</code> matrix (array of rank 2).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param sizeX The size of the x dimension of the short matrix to create.
     * @param sizeY The size of the y dimension of the short matrix to create.
     * @param blockSizeX The size of one block in the x dimension.
     * @param blockSizeY The size of one block in the y dimension.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void createShortMatrix(final String objectPath, final long sizeX, final long sizeY,
            int blockSizeX, int blockSizeY, boolean deflate)
    {
        assert objectPath != null;
        assert sizeX >= 0;
        assert sizeY >= 0;
        assert blockSizeX >= 0 && blockSizeX <= sizeX;
        assert blockSizeY >= 0 && blockSizeY <= sizeY;

        checkOpen();
        writeBlockRankN(objectPath, H5T_STD_I16LE, H5T_NATIVE_INT16, getDeflateLevel(deflate),
                null, new long[]
                    { sizeX, sizeY }, new long[]
                    { blockSizeX, blockSizeY }, new long[]
                    { 0, 0 });
    }

    /**
     * Writes out a block of a <code>short</code> matrix (array of rank 2). The data set needs to
     * have been created by {@link #createShortMatrix(String, long, long, int, int, boolean)}
     * beforehand.
     * <p>
     * Use this method instead of {@link #createShortMatrix(String, long, long, int, int, boolean)}
     * if the total size of the data set is not a multiple of the block size.
     * <p>
     * <i>Note:</i> For best performance, the size of <var>data</var> in this method should match
     * the <var>blockSizeX/Y</var> arguments of the
     * {@link #createShortMatrix(String, long, long, int, int, boolean)} call that was used to
     * create the data set.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. The length defines the block size. Must not be
     *            <code>null</code> or of length 0.
     * @param blockNumberX The block number in the x dimension (offset: multiply with
     *            <code>data.length</code>).
     * @param blockNumberY The block number in the y dimension (offset: multiply with
     *            <code>data[0.length</code>).
     */
    public void writeShortMatrixBlock(final String objectPath, final short[][] data,
            final long blockNumberX, final long blockNumberY)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        final long[] blockDimensions = new long[]
            { data.length, data[0].length };
        writeBlockRankN(objectPath, H5T_STD_I16LE, H5T_NATIVE_INT16, HDF5.NO_DEFLATION, data,
                blockDimensions, blockDimensions, new long[]
                    { blockNumberX * data.length, blockNumberY * data[0].length });
    }

    /**
     * Writes out a block of a <code>short</code> matrix (array of rank 2). The data set needs to
     * have been created by {@link #createShortMatrix(String, long, long, int, int, boolean)}
     * beforehand.
     * <p>
     * Use this method instead of {@link #writeShortMatrixBlock(String, short[][], long, long)} if
     * the total size of the data set is not a multiple of the block size.
     * <p>
     * <i>Note:</i> For best performance, the typical <var>dataSize</var> in this method should be
     * chosen to be equal to the <var>blockSize</var> argument of the
     * {@link #createShortMatrix(String, long, long, int, int, boolean)} call that was used to
     * create the data set.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write.
     * @param offsetX The x offset in the data set to start writing to.
     * @param offsetY The y offset in the data set to start writing to.
     */
    public void writeShortMatrixBlockWithOffset(final String objectPath, final short[][] data,
            final long offsetX, final long offsetY)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        final long[] blockDimensions = new long[]
            { data.length, data[0].length };
        writeBlockRankN(objectPath, H5T_STD_I16LE, H5T_NATIVE_INT16, HDF5.NO_DEFLATION, data,
                blockDimensions, blockDimensions, new long[]
                    { offsetX, offsetY });
    }

    /**
     * Writes out a multi-dimensional <code>short</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     */
    public void writeShortMDArray(final String objectPath, final MDShortArray data)
    {
        writeShortMDArray(objectPath, data, false);
    }

    /**
     * Writes out a multi-dimensional <code>short</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void writeShortMDArray(final String objectPath, final MDShortArray data,
            final boolean deflate)
    {
        assert data != null;

        checkOpen();
        writeRankN(objectPath, H5T_STD_I16LE, H5T_NATIVE_INT16, getDeflateLevel(deflate), data
                .getAsFlatArray(), data.longDimensions());
    }

    /**
     * Creates a multi-dimensional <code>short</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param dimensions The dimensions of the array.
     * @param blockDimensions The dimensions of one block (chunk) of the array.
     */
    public void createShortMDArray(final String objectPath, final long[] dimensions,
            final int[] blockDimensions)
    {
        createShortMDArray(objectPath, dimensions, blockDimensions, false);
    }

    /**
     * Creates a multi-dimensional <code>short</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param dimensions The dimensions of the array.
     * @param blockDimensions The dimensions of one block (chunk) of the array.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void createShortMDArray(final String objectPath, final long[] dimensions,
            final int[] blockDimensions, final boolean deflate)
    {
        assert objectPath != null;
        assert dimensions != null;
        assert blockDimensions != null;

        checkOpen();
        writeBlockRankN(objectPath, H5T_STD_I16LE, H5T_NATIVE_INT16, getDeflateLevel(deflate),
                null, dimensions, MDArray.toLong(blockDimensions), null);
    }

    /**
     * Writes out a block of a multi-dimensional <code>short</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     * @param blockNumber The block number in each dimension (offset: multiply with the extend in
     *            the according dimension).
     */
    public void writeShortMDArrayBlock(final String objectPath, final MDShortArray data,
            final long[] blockNumber)
    {
        assert data != null;
        assert blockNumber != null;

        checkOpen();
        final long[] dimensions = data.longDimensions();
        assert dimensions.length == blockNumber.length;
        final long[] offset = new long[dimensions.length];
        for (int i = 0; i < offset.length; ++i)
        {
            offset[i] = blockNumber[i] * dimensions[i];
        }
        writeBlockRankN(objectPath, H5T_STD_I16LE, H5T_NATIVE_INT16, HDF5.NO_DEFLATION, data
                .getAsFlatArray(), dimensions, dimensions, offset);
    }

    /**
     * Writes out a block of a multi-dimensional <code>short</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     * @param offset The offset in the data set to start writing to in each dimension.
     */
    public void writeShortMDArrayBlockWithOffset(final String objectPath, final MDShortArray data,
            final long[] offset)
    {
        assert data != null;
        assert offset != null;

        checkOpen();
        final long[] dimensions = data.longDimensions();
        assert dimensions.length == offset.length;
        writeBlockRankN(objectPath, H5T_STD_I16LE, H5T_NATIVE_INT16, HDF5.NO_DEFLATION, data
                .getAsFlatArray(), dimensions, dimensions, offset);
    }

    //
    // Int
    //

    /**
     * Writes out a <code>int</code> value.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param value The value to write.
     */
    public void writeInt(final String objectPath, final int value)
    {
        assert objectPath != null;

        checkOpen();
        writeScalar(objectPath, H5T_STD_I32LE, H5T_NATIVE_INT32, HDFNativeData.intToByte(value));
    }

    /**
     * Creates a <code>int</code> array (of rank 1). Uses a compact storage layout. Should only be
     * used for small data sets.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param length The length of the data set to create.
     */
    public void createIntArrayCompact(final String objectPath, final long length)
    {
        assert objectPath != null;
        assert length > 0;

        checkOpen();
        writeRank1Compact(objectPath, H5T_STD_I32LE, H5T_NATIVE_INT32, null, length);
    }

    /**
     * Writes out a <code>int</code> array (of rank 1). Uses a compact storage layout. Should only
     * be used for small data sets.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     */
    public void writeIntArrayCompact(final String objectPath, final int[] data)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        writeRank1Compact(objectPath, H5T_STD_I32LE, H5T_NATIVE_INT32, data, data.length);
    }

    /**
     * Writes out a <code>int</code> array (of rank 1).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     */
    public void writeIntArray(final String objectPath, final int[] data)
    {
        writeIntArray(objectPath, data, false);
    }

    /**
     * Writes out a <code>int</code> array (of rank 1).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void writeIntArray(final String objectPath, final int[] data, boolean deflate)
    {
        assert data != null;

        checkOpen();
        writeRank1(objectPath, H5T_STD_I32LE, H5T_NATIVE_INT32, getDeflateLevel(deflate), data,
                data.length);
    }

    /**
     * Creates a <code>int</code> array (of rank 1).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param size The size of the int vector to create. When using extendable data sets ((see
     *            {@link #dontUseExtendableDataTypes()})), then no data set smaller than this size
     *            can be created, however data sets may be larger.
     * @param blockSize The size of one block (for block-wise IO). Ignored if no extendable data
     *            sets are used (see {@link #dontUseExtendableDataTypes()}).
     */
    public void createIntArray(final String objectPath, final long size, final int blockSize)
    {
        assert objectPath != null;
        assert size >= 0;
        assert blockSize >= 0 && blockSize <= size;

        checkOpen();
        createIntArray(objectPath, size, blockSize, false);
    }

    /**
     * Creates a <code>int</code> array (of rank 1).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param size The size of the int array to create. When using extendable data sets ((see
     *            {@link #dontUseExtendableDataTypes()})), then no data set smaller than this size
     *            can be created, however data sets may be larger.
     * @param blockSize The size of one block (for block-wise IO). Ignored if no extendable data
     *            sets are used (see {@link #dontUseExtendableDataTypes()}) and
     *            <code>deflate == false</code>.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void createIntArray(final String objectPath, final long size, final int blockSize,
            boolean deflate)
    {
        assert objectPath != null;
        assert size >= 0;
        assert blockSize >= 0 && blockSize <= size;

        checkOpen();
        writeBlockRank1(objectPath, H5T_STD_I32LE, H5T_NATIVE_INT32, getDeflateLevel(deflate),
                null, size, blockSize, 0);
    }

    /**
     * Writes out a block of a <code>int</code> array (of rank 1). The data set needs to have been
     * created by {@link #createIntArray(String, long, int, boolean)} beforehand.
     * <p>
     * <i>Note:</i> For best performance, the block size in this method should be chosen to be equal
     * to the <var>blockSize</var> argument of the
     * {@link #createIntArray(String, long, int, boolean)} call that was used to create the data
     * set.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. The length defines the block size. Must not be
     *            <code>null</code> or of length 0.
     * @param blockNumber The number of the block to write.
     */
    public void writeIntArrayBlock(final String objectPath, final int[] data, final long blockNumber)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        writeBlockRank1(objectPath, H5T_STD_I32LE, H5T_NATIVE_INT32, HDF5.NO_DEFLATION, data,
                data.length, data.length, data.length * blockNumber);
    }

    /**
     * Writes out a block of a <code>int</code> array (of rank 1). The data set needs to have been
     * created by {@link #createIntArray(String, long, int, boolean)} beforehand.
     * <p>
     * Use this method instead of {@link #writeIntArrayBlock(String, int[], long)} if the total size
     * of the data set is not a multiple of the block size.
     * <p>
     * <i>Note:</i> For best performance, the typical <var>dataSize</var> in this method should be
     * chosen to be equal to the <var>blockSize</var> argument of the
     * {@link #createIntArray(String, long, int, boolean)} call that was used to create the data
     * set.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. The length defines the block size. Must not be
     *            <code>null</code> or of length 0.
     * @param dataSize The (real) size of <code>data</code> (needs to be <code><= data.length</code>
     *            )
     * @param offset The offset in the data set to start writing to.
     */
    public void writeIntArrayBlockWithOffset(final String objectPath, final int[] data,
            final int dataSize, final long offset)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        writeBlockRank1(objectPath, H5T_STD_I32LE, H5T_NATIVE_INT32, HDF5.NO_DEFLATION, data,
                dataSize, dataSize, offset);
    }

    /**
     * Writes out a <code>int</code> matrix (array of rank 2).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     */
    public void writeIntMatrix(final String objectPath, final int[][] data)
    {
        writeIntMatrix(objectPath, data, false);
    }

    /**
     * Writes out a <code>int</code> matrix (array of rank 2).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void writeIntMatrix(final String objectPath, final int[][] data, boolean deflate)
    {
        assert data != null;
        assert checkDimensions(data);

        checkOpen();
        writeRankN(objectPath, H5T_STD_I32LE, H5T_NATIVE_INT32, getDeflateLevel(deflate), data,
                new long[]
                    { data.length, data.length == 0 ? 0 : data[0].length });
    }

    /**
     * Creates a <code>int</code> matrix (array of rank 2).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param sizeX The size of the x dimension of the int matrix to create.
     * @param sizeY The size of the y dimension of the int matrix to create.
     * @param blockSizeX The size of one block in the x dimension.
     * @param blockSizeY The size of one block in the y dimension.
     */
    public void createIntMatrix(final String objectPath, final long sizeX, final long sizeY,
            int blockSizeX, int blockSizeY)
    {
        createIntMatrix(objectPath, sizeX, sizeY, blockSizeX, blockSizeY, false);
    }

    /**
     * Creates a <code>int</code> matrix (array of rank 2).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param sizeX The size of the x dimension of the int matrix to create.
     * @param sizeY The size of the y dimension of the int matrix to create.
     * @param blockSizeX The size of one block in the x dimension.
     * @param blockSizeY The size of one block in the y dimension.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void createIntMatrix(final String objectPath, final long sizeX, final long sizeY,
            int blockSizeX, int blockSizeY, boolean deflate)
    {
        assert objectPath != null;
        assert sizeX >= 0;
        assert sizeY >= 0;
        assert blockSizeX >= 0 && blockSizeX <= sizeX;
        assert blockSizeY >= 0 && blockSizeY <= sizeY;

        checkOpen();
        writeBlockRankN(objectPath, H5T_STD_I32LE, H5T_NATIVE_INT32, getDeflateLevel(deflate),
                null, new long[]
                    { sizeX, sizeY }, new long[]
                    { blockSizeX, blockSizeY }, new long[]
                    { 0, 0 });
    }

    /**
     * Writes out a block of a <code>int</code> matrix (array of rank 2). The data set needs to have
     * been created by {@link #createIntMatrix(String, long, long, int, int, boolean)} beforehand.
     * <p>
     * Use this method instead of {@link #createIntMatrix(String, long, long, int, int, boolean)} if
     * the total size of the data set is not a multiple of the block size.
     * <p>
     * <i>Note:</i> For best performance, the size of <var>data</var> in this method should match
     * the <var>blockSizeX/Y</var> arguments of the
     * {@link #createIntMatrix(String, long, long, int, int, boolean)} call that was used to create
     * the data set.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. The length defines the block size. Must not be
     *            <code>null</code> or of length 0.
     * @param blockNumberX The block number in the x dimension (offset: multiply with
     *            <code>data.length</code>).
     * @param blockNumberY The block number in the y dimension (offset: multiply with
     *            <code>data[0.length</code>).
     */
    public void writeIntMatrixBlock(final String objectPath, final int[][] data,
            final long blockNumberX, final long blockNumberY)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        final long[] blockDimensions = new long[]
            { data.length, data[0].length };
        writeBlockRankN(objectPath, H5T_STD_I32LE, H5T_NATIVE_INT32, HDF5.NO_DEFLATION, data,
                blockDimensions, blockDimensions, new long[]
                    { blockNumberX * data.length, blockNumberY * data[0].length });
    }

    /**
     * Writes out a block of a <code>int</code> matrix (array of rank 2). The data set needs to have
     * been created by {@link #createIntMatrix(String, long, long, int, int, boolean)} beforehand.
     * <p>
     * Use this method instead of {@link #writeIntMatrixBlock(String, int[][], long, long)} if the
     * total size of the data set is not a multiple of the block size.
     * <p>
     * <i>Note:</i> For best performance, the typical <var>dataSize</var> in this method should be
     * chosen to be equal to the <var>blockSize</var> argument of the
     * {@link #createIntMatrix(String, long, long, int, int, boolean)} call that was used to create
     * the data set.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write.
     * @param offsetX The x offset in the data set to start writing to.
     * @param offsetY The y offset in the data set to start writing to.
     */
    public void writeIntMatrixBlockWithOffset(final String objectPath, final int[][] data,
            final long offsetX, final long offsetY)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        final long[] blockDimensions = new long[]
            { data.length, data[0].length };
        writeBlockRankN(objectPath, H5T_STD_I32LE, H5T_NATIVE_INT32, HDF5.NO_DEFLATION, data,
                blockDimensions, blockDimensions, new long[]
                    { offsetX, offsetY });
    }

    /**
     * Writes out a multi-dimensional <code>int</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     */
    public void writeIntMDArray(final String objectPath, final MDIntArray data)
    {
        writeIntMDArray(objectPath, data, false);
    }

    /**
     * Writes out a multi-dimensional <code>int</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void writeIntMDArray(final String objectPath, final MDIntArray data,
            final boolean deflate)
    {
        assert data != null;

        checkOpen();
        writeRankN(objectPath, H5T_STD_I32LE, H5T_NATIVE_INT32, getDeflateLevel(deflate), data
                .getAsFlatArray(), data.longDimensions());
    }

    /**
     * Creates a multi-dimensional <code>int</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param dimensions The dimensions of the array.
     * @param blockDimensions The dimensions of one block (chunk) of the array.
     */
    public void createIntMDArray(final String objectPath, final long[] dimensions,
            final int[] blockDimensions)
    {
        createIntMDArray(objectPath, dimensions, blockDimensions, false);
    }

    /**
     * Creates a multi-dimensional <code>int</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param dimensions The dimensions of the array.
     * @param blockDimensions The dimensions of one block (chunk) of the array.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void createIntMDArray(final String objectPath, final long[] dimensions,
            final int[] blockDimensions, final boolean deflate)
    {
        assert objectPath != null;
        assert dimensions != null;
        assert blockDimensions != null;

        checkOpen();
        writeBlockRankN(objectPath, H5T_STD_I32LE, H5T_NATIVE_INT32, getDeflateLevel(deflate),
                null, dimensions, MDArray.toLong(blockDimensions), null);
    }

    /**
     * Writes out a block of a multi-dimensional <code>int</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     * @param blockNumber The block number in each dimension (offset: multiply with the extend in
     *            the according dimension).
     */
    public void writeIntMDArrayBlock(final String objectPath, final MDIntArray data,
            final long[] blockNumber)
    {
        assert data != null;
        assert blockNumber != null;

        checkOpen();
        final long[] dimensions = data.longDimensions();
        assert dimensions.length == blockNumber.length;
        final long[] offset = new long[dimensions.length];
        for (int i = 0; i < offset.length; ++i)
        {
            offset[i] = blockNumber[i] * dimensions[i];
        }
        writeBlockRankN(objectPath, H5T_STD_I32LE, H5T_NATIVE_INT32, HDF5.NO_DEFLATION, data
                .getAsFlatArray(), dimensions, dimensions, offset);
    }

    /**
     * Writes out a block of a multi-dimensional <code>int</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     * @param offset The offset in the data set to start writing to in each dimension.
     */
    public void writeIntMDArrayBlockWithOffset(final String objectPath, final MDIntArray data,
            final long[] offset)
    {
        assert data != null;
        assert offset != null;

        checkOpen();
        final long[] dimensions = data.longDimensions();
        assert dimensions.length == offset.length;
        writeBlockRankN(objectPath, H5T_STD_I32LE, H5T_NATIVE_INT32, HDF5.NO_DEFLATION, data
                .getAsFlatArray(), dimensions, dimensions, offset);
    }

    //
    // Long
    //

    /**
     * Writes out a <code>long</code> value.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param value The value to write.
     */
    public void writeLong(final String objectPath, final long value)
    {
        assert objectPath != null;

        checkOpen();
        writeScalar(objectPath, H5T_STD_I64LE, H5T_NATIVE_INT64, HDFNativeData.longToByte(value));
    }

    /**
     * Creates a <code>long</code> array (of rank 1). Uses a compact storage layout. Should only be
     * used for small data sets.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param length The length of the data set to create.
     */
    public void createLongArrayCompact(final String objectPath, final long length)
    {
        assert objectPath != null;
        assert length > 0;

        checkOpen();
        writeRank1Compact(objectPath, H5T_STD_I64LE, H5T_NATIVE_INT64, null, length);
    }

    /**
     * Writes out a <code>long</code> array (of rank 1). Uses a compact storage layout. Should only
     * be used for small data sets.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     */
    public void writeLongArrayCompact(final String objectPath, final long[] data)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        writeRank1Compact(objectPath, H5T_STD_I64LE, H5T_NATIVE_INT64, data, data.length);
    }

    /**
     * Writes out a <code>long</code> array (of rank 1).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     */
    public void writeLongArray(final String objectPath, final long[] data)
    {
        writeLongArray(objectPath, data, false);
    }

    /**
     * Writes out a <code>long</code> array (of rank 1).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void writeLongArray(final String objectPath, final long[] data, boolean deflate)
    {
        assert data != null;

        checkOpen();
        writeRank1(objectPath, H5T_STD_I64LE, H5T_NATIVE_INT64, getDeflateLevel(deflate), data,
                data.length);
    }

    /**
     * Creates a <code>long</code> array (of rank 1).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param size The size of the long vector to create. When using extendable data sets ((see
     *            {@link #dontUseExtendableDataTypes()})), then no data set smaller than this size
     *            can be created, however data sets may be larger.
     * @param blockSize The size of one block (for block-wise IO). Ignored if no extendable data
     *            sets are used (see {@link #dontUseExtendableDataTypes()}).
     */
    public void createLongArray(final String objectPath, final long size, final int blockSize)
    {
        assert objectPath != null;
        assert size >= 0;
        assert blockSize >= 0 && blockSize <= size;

        checkOpen();
        createLongArray(objectPath, size, blockSize, false);
    }

    /**
     * Creates a <code>long</code> array (of rank 1).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param size The size of the long array to create. When using extendable data sets ((see
     *            {@link #dontUseExtendableDataTypes()})), then no data set smaller than this size
     *            can be created, however data sets may be larger.
     * @param blockSize The size of one block (for block-wise IO). Ignored if no extendable data
     *            sets are used (see {@link #dontUseExtendableDataTypes()}) and
     *            <code>deflate == false</code>.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void createLongArray(final String objectPath, final long size, final int blockSize,
            boolean deflate)
    {
        assert objectPath != null;
        assert size >= 0;
        assert blockSize >= 0 && blockSize <= size;

        checkOpen();
        writeBlockRank1(objectPath, H5T_STD_I64LE, H5T_NATIVE_INT64, getDeflateLevel(deflate),
                null, size, blockSize, 0);
    }

    /**
     * Writes out a block of a <code>long</code> array (of rank 1). The data set needs to have been
     * created by {@link #createLongArray(String, long, int, boolean)} beforehand.
     * <p>
     * <i>Note:</i> For best performance, the block size in this method should be chosen to be equal
     * to the <var>blockSize</var> argument of the
     * {@link #createLongArray(String, long, int, boolean)} call that was used to create the data
     * set.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. The length defines the block size. Must not be
     *            <code>null</code> or of length 0.
     * @param blockNumber The number of the block to write.
     */
    public void writeLongArrayBlock(final String objectPath, final long[] data,
            final long blockNumber)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        writeBlockRank1(objectPath, H5T_STD_I64LE, H5T_NATIVE_INT64, HDF5.NO_DEFLATION, data,
                data.length, data.length, data.length * blockNumber);
    }

    /**
     * Writes out a block of a <code>long</code> array (of rank 1). The data set needs to have been
     * created by {@link #createLongArray(String, long, int, boolean)} beforehand.
     * <p>
     * Use this method instead of {@link #writeLongArrayBlock(String, long[], long)} if the total
     * size of the data set is not a multiple of the block size.
     * <p>
     * <i>Note:</i> For best performance, the typical <var>dataSize</var> in this method should be
     * chosen to be equal to the <var>blockSize</var> argument of the
     * {@link #createLongArray(String, long, int, boolean)} call that was used to create the data
     * set.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. The length defines the block size. Must not be
     *            <code>null</code> or of length 0.
     * @param dataSize The (real) size of <code>data</code> (needs to be <code><= data.length</code>
     *            )
     * @param offset The offset in the data set to start writing to.
     */
    public void writeLongArrayBlockWithOffset(final String objectPath, final long[] data,
            final int dataSize, final long offset)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        writeBlockRank1(objectPath, H5T_STD_I64LE, H5T_NATIVE_INT64, HDF5.NO_DEFLATION, data,
                dataSize, dataSize, offset);
    }

    /**
     * Writes out a <code>long</code> matrix (array of rank 2).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     */
    public void writeLongMatrix(final String objectPath, final long[][] data)
    {
        writeLongMatrix(objectPath, data, false);
    }

    /**
     * Writes out a <code>long</code> matrix (array of rank 2).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void writeLongMatrix(final String objectPath, final long[][] data, boolean deflate)
    {
        assert data != null;
        assert checkDimensions(data);

        checkOpen();
        writeRankN(objectPath, H5T_STD_I64LE, H5T_NATIVE_INT64, getDeflateLevel(deflate), data,
                new long[]
                    { data.length, data.length == 0 ? 0 : data[0].length });
    }

    /**
     * Creates a <code>long</code> matrix (array of rank 2).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param sizeX The size of the x dimension of the long matrix to create.
     * @param sizeY The size of the y dimension of the long matrix to create.
     * @param blockSizeX The size of one block in the x dimension.
     * @param blockSizeY The size of one block in the y dimension.
     */
    public void createLongMatrix(final String objectPath, final long sizeX, final long sizeY,
            int blockSizeX, int blockSizeY)
    {
        createLongMatrix(objectPath, sizeX, sizeY, blockSizeX, blockSizeY, false);
    }

    /**
     * Creates a <code>long</code> matrix (array of rank 2).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param sizeX The size of the x dimension of the long matrix to create.
     * @param sizeY The size of the y dimension of the long matrix to create.
     * @param blockSizeX The size of one block in the x dimension.
     * @param blockSizeY The size of one block in the y dimension.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void createLongMatrix(final String objectPath, final long sizeX, final long sizeY,
            int blockSizeX, int blockSizeY, boolean deflate)
    {
        assert objectPath != null;
        assert sizeX >= 0;
        assert sizeY >= 0;
        assert blockSizeX >= 0 && blockSizeX <= sizeX;
        assert blockSizeY >= 0 && blockSizeY <= sizeY;

        checkOpen();
        writeBlockRankN(objectPath, H5T_STD_I64LE, H5T_NATIVE_INT64, getDeflateLevel(deflate),
                null, new long[]
                    { sizeX, sizeY }, new long[]
                    { blockSizeX, blockSizeY }, new long[]
                    { 0, 0 });
    }

    /**
     * Writes out a block of a <code>long</code> matrix (array of rank 2). The data set needs to
     * have been created by {@link #createLongMatrix(String, long, long, int, int, boolean)}
     * beforehand.
     * <p>
     * Use this method instead of {@link #createLongMatrix(String, long, long, int, int, boolean)}
     * if the total size of the data set is not a multiple of the block size.
     * <p>
     * <i>Note:</i> For best performance, the size of <var>data</var> in this method should match
     * the <var>blockSizeX/Y</var> arguments of the
     * {@link #createLongMatrix(String, long, long, int, int, boolean)} call that was used to create
     * the data set.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. The length defines the block size. Must not be
     *            <code>null</code> or of length 0.
     * @param blockNumberX The block number in the x dimension (offset: multiply with
     *            <code>data.length</code>).
     * @param blockNumberY The block number in the y dimension (offset: multiply with
     *            <code>data[0.length</code>).
     */
    public void writeLongMatrixBlock(final String objectPath, final long[][] data,
            final long blockNumberX, final long blockNumberY)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        final long[] blockDimensions = new long[]
            { data.length, data[0].length };
        writeBlockRankN(objectPath, H5T_STD_I64LE, H5T_NATIVE_INT64, HDF5.NO_DEFLATION, data,
                blockDimensions, blockDimensions, new long[]
                    { blockNumberX * data.length, blockNumberY * data[0].length });
    }

    /**
     * Writes out a block of a <code>long</code> matrix (array of rank 2). The data set needs to
     * have been created by {@link #createLongMatrix(String, long, long, int, int, boolean)}
     * beforehand.
     * <p>
     * Use this method instead of {@link #writeLongMatrixBlock(String, long[][], long, long)} if the
     * total size of the data set is not a multiple of the block size.
     * <p>
     * <i>Note:</i> For best performance, the typical <var>dataSize</var> in this method should be
     * chosen to be equal to the <var>blockSize</var> argument of the
     * {@link #createLongMatrix(String, long, long, int, int, boolean)} call that was used to create
     * the data set.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write.
     * @param offsetX The x offset in the data set to start writing to.
     * @param offsetY The y offset in the data set to start writing to.
     */
    public void writeLongMatrixBlockWithOffset(final String objectPath, final long[][] data,
            final long offsetX, final long offsetY)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        final long[] blockDimensions = new long[]
            { data.length, data[0].length };
        writeBlockRankN(objectPath, H5T_STD_I64LE, H5T_NATIVE_INT64, HDF5.NO_DEFLATION, data,
                blockDimensions, blockDimensions, new long[]
                    { offsetX, offsetY });
    }

    /**
     * Writes out a multi-dimensional <code>long</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     */
    public void writeLongMDArray(final String objectPath, final MDLongArray data)
    {
        writeLongMDArray(objectPath, data, false);
    }

    /**
     * Writes out a multi-dimensional <code>long</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void writeLongMDArray(final String objectPath, final MDLongArray data,
            final boolean deflate)
    {
        assert data != null;

        checkOpen();
        writeRankN(objectPath, H5T_STD_I64LE, H5T_NATIVE_INT64, getDeflateLevel(deflate), data
                .getAsFlatArray(), data.longDimensions());
    }

    /**
     * Creates a multi-dimensional <code>long</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param dimensions The dimensions of the array.
     * @param blockDimensions The dimensions of one block (chunk) of the array.
     */
    public void createLongMDArray(final String objectPath, final long[] dimensions,
            final int[] blockDimensions)
    {
        createLongMDArray(objectPath, dimensions, blockDimensions, false);
    }

    /**
     * Creates a multi-dimensional <code>long</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param dimensions The dimensions of the array.
     * @param blockDimensions The dimensions of one block (chunk) of the array.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void createLongMDArray(final String objectPath, final long[] dimensions,
            final int[] blockDimensions, final boolean deflate)
    {
        assert objectPath != null;
        assert dimensions != null;
        assert blockDimensions != null;

        checkOpen();
        writeBlockRankN(objectPath, H5T_STD_I64LE, H5T_NATIVE_INT64, getDeflateLevel(deflate),
                null, dimensions, MDArray.toLong(blockDimensions), null);
    }

    /**
     * Writes out a block of a multi-dimensional <code>long</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     * @param blockNumber The block number in each dimension (offset: multiply with the extend in
     *            the according dimension).
     */
    public void writeLongMDArrayBlock(final String objectPath, final MDLongArray data,
            final long[] blockNumber)
    {
        assert data != null;
        assert blockNumber != null;

        checkOpen();
        final long[] dimensions = data.longDimensions();
        assert dimensions.length == blockNumber.length;
        final long[] offset = new long[dimensions.length];
        for (int i = 0; i < offset.length; ++i)
        {
            offset[i] = blockNumber[i] * dimensions[i];
        }
        writeBlockRankN(objectPath, H5T_STD_I64LE, H5T_NATIVE_INT64, HDF5.NO_DEFLATION, data
                .getAsFlatArray(), dimensions, dimensions, offset);
    }

    /**
     * Writes out a block of a multi-dimensional <code>long</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     * @param offset The offset in the data set to start writing to in each dimension.
     */
    public void writeLongMDArrayBlockWithOffset(final String objectPath, final MDLongArray data,
            final long[] offset)
    {
        assert data != null;
        assert offset != null;

        checkOpen();
        final long[] dimensions = data.longDimensions();
        assert dimensions.length == offset.length;
        writeBlockRankN(objectPath, H5T_STD_I64LE, H5T_NATIVE_INT64, HDF5.NO_DEFLATION, data
                .getAsFlatArray(), dimensions, dimensions, offset);
    }

    //
    // Float
    //

    /**
     * Writes out a <code>float</code> value.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param value The value to write.
     */
    public void writeFloat(final String objectPath, final float value)
    {
        assert objectPath != null;

        checkOpen();
        writeScalar(objectPath, H5T_IEEE_F32LE, H5T_NATIVE_FLOAT, HDFNativeData.floatToByte(value));
    }

    /**
     * Creates a <code>float</code> array (of rank 1). Uses a compact storage layout. Should only be
     * used for small data sets.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param length The length of the data set to create.
     */
    public void createFloatArrayCompact(final String objectPath, final long length)
    {
        assert objectPath != null;
        assert length > 0;

        checkOpen();
        writeRank1Compact(objectPath, H5T_IEEE_F32LE, H5T_NATIVE_FLOAT, null, length);
    }

    /**
     * Writes out a <code>float</code> array (of rank 1). Uses a compact storage layout. Should only
     * be used for small data sets.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     */
    public void writeFloatArrayCompact(final String objectPath, final float[] data)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        writeRank1Compact(objectPath, H5T_IEEE_F32LE, H5T_NATIVE_FLOAT, data, data.length);
    }

    /**
     * Writes out a <code>float</code> array (of rank 1).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     */
    public void writeFloatArray(final String objectPath, final float[] data)
    {
        writeFloatArray(objectPath, data, false);
    }

    /**
     * Writes out a <code>float</code> array (of rank 1).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void writeFloatArray(final String objectPath, final float[] data, boolean deflate)
    {
        assert data != null;

        checkOpen();
        writeRank1(objectPath, H5T_IEEE_F32LE, H5T_NATIVE_FLOAT, getDeflateLevel(deflate), data,
                data.length);
    }

    /**
     * Creates a <code>float</code> array (of rank 1).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param size The size of the float vector to create. When using extendable data sets ((see
     *            {@link #dontUseExtendableDataTypes()})), then no data set smaller than this size
     *            can be created, however data sets may be larger.
     * @param blockSize The size of one block (for block-wise IO). Ignored if no extendable data
     *            sets are used (see {@link #dontUseExtendableDataTypes()}).
     */
    public void createFloatArray(final String objectPath, final long size, final int blockSize)
    {
        assert objectPath != null;
        assert size >= 0;
        assert blockSize >= 0 && blockSize <= size;

        checkOpen();
        createFloatArray(objectPath, size, blockSize, false);
    }

    /**
     * Creates a <code>float</code> array (of rank 1).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param size The size of the float array to create. When using extendable data sets ((see
     *            {@link #dontUseExtendableDataTypes()})), then no data set smaller than this size
     *            can be created, however data sets may be larger.
     * @param blockSize The size of one block (for block-wise IO). Ignored if no extendable data
     *            sets are used (see {@link #dontUseExtendableDataTypes()}) and
     *            <code>deflate == false</code>.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void createFloatArray(final String objectPath, final long size, final int blockSize,
            boolean deflate)
    {
        assert objectPath != null;
        assert size >= 0;
        assert blockSize >= 0 && blockSize <= size;

        checkOpen();
        writeBlockRank1(objectPath, H5T_IEEE_F32LE, H5T_NATIVE_FLOAT, getDeflateLevel(deflate),
                null, size, blockSize, 0);
    }

    /**
     * Writes out a block of a <code>float</code> array (of rank 1). The data set needs to have been
     * created by {@link #createFloatArray(String, long, int, boolean)} beforehand.
     * <p>
     * <i>Note:</i> For best performance, the block size in this method should be chosen to be equal
     * to the <var>blockSize</var> argument of the
     * {@link #createFloatArray(String, long, int, boolean)} call that was used to create the data
     * set.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. The length defines the block size. Must not be
     *            <code>null</code> or of length 0.
     * @param blockNumber The number of the block to write.
     */
    public void writeFloatArrayBlock(final String objectPath, final float[] data,
            final long blockNumber)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        writeBlockRank1(objectPath, H5T_IEEE_F32LE, H5T_NATIVE_FLOAT, HDF5.NO_DEFLATION, data,
                data.length, data.length, data.length * blockNumber);
    }

    /**
     * Writes out a block of a <code>float</code> array (of rank 1). The data set needs to have been
     * created by {@link #createFloatArray(String, long, int, boolean)} beforehand.
     * <p>
     * Use this method instead of {@link #writeFloatArrayBlock(String, float[], long)} if the total
     * size of the data set is not a multiple of the block size.
     * <p>
     * <i>Note:</i> For best performance, the typical <var>dataSize</var> in this method should be
     * chosen to be equal to the <var>blockSize</var> argument of the
     * {@link #createFloatArray(String, long, int, boolean)} call that was used to create the data
     * set.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. The length defines the block size. Must not be
     *            <code>null</code> or of length 0.
     * @param dataSize The (real) size of <code>data</code> (needs to be <code><= data.length</code>
     *            )
     * @param offset The offset in the data set to start writing to.
     */
    public void writeFloatArrayBlockWithOffset(final String objectPath, final float[] data,
            final int dataSize, final long offset)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        writeBlockRank1(objectPath, H5T_IEEE_F32LE, H5T_NATIVE_FLOAT, HDF5.NO_DEFLATION, data,
                dataSize, dataSize, offset);
    }

    /**
     * Writes out a <code>float</code> matrix (array of rank 2).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     */
    public void writeFloatMatrix(final String objectPath, final float[][] data)
    {
        writeFloatMatrix(objectPath, data, false);
    }

    /**
     * Writes out a <code>float</code> matrix (array of rank 2).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void writeFloatMatrix(final String objectPath, final float[][] data, boolean deflate)
    {
        assert data != null;
        assert checkDimensions(data);

        checkOpen();
        writeRankN(objectPath, H5T_IEEE_F32LE, H5T_NATIVE_FLOAT, getDeflateLevel(deflate), data,
                new long[]
                    { data.length, data.length == 0 ? 0 : data[0].length });
    }

    /**
     * Creates a <code>float</code> matrix (array of rank 2).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param sizeX The size of the x dimension of the float matrix to create.
     * @param sizeY The size of the y dimension of the float matrix to create.
     * @param blockSizeX The size of one block in the x dimension.
     * @param blockSizeY The size of one block in the y dimension.
     */
    public void createFloatMatrix(final String objectPath, final long sizeX, final long sizeY,
            int blockSizeX, int blockSizeY)
    {
        createFloatMatrix(objectPath, sizeX, sizeY, blockSizeX, blockSizeY, false);
    }

    /**
     * Creates a <code>float</code> matrix (array of rank 2).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param sizeX The size of the x dimension of the float matrix to create.
     * @param sizeY The size of the y dimension of the float matrix to create.
     * @param blockSizeX The size of one block in the x dimension.
     * @param blockSizeY The size of one block in the y dimension.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void createFloatMatrix(final String objectPath, final long sizeX, final long sizeY,
            int blockSizeX, int blockSizeY, boolean deflate)
    {
        assert objectPath != null;
        assert sizeX >= 0;
        assert sizeY >= 0;
        assert blockSizeX >= 0 && blockSizeX <= sizeX;
        assert blockSizeY >= 0 && blockSizeY <= sizeY;

        checkOpen();
        writeBlockRankN(objectPath, H5T_IEEE_F32LE, H5T_NATIVE_FLOAT, getDeflateLevel(deflate),
                null, new long[]
                    { sizeX, sizeY }, new long[]
                    { blockSizeX, blockSizeY }, new long[]
                    { 0, 0 });
    }

    /**
     * Writes out a block of a <code>float</code> matrix (array of rank 2). The data set needs to
     * have been created by {@link #createFloatMatrix(String, long, long, int, int, boolean)}
     * beforehand.
     * <p>
     * Use this method instead of {@link #createFloatMatrix(String, long, long, int, int, boolean)}
     * if the total size of the data set is not a multiple of the block size.
     * <p>
     * <i>Note:</i> For best performance, the size of <var>data</var> in this method should match
     * the <var>blockSizeX/Y</var> arguments of the
     * {@link #createFloatMatrix(String, long, long, int, int, boolean)} call that was used to
     * create the data set.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. The length defines the block size. Must not be
     *            <code>null</code> or of length 0.
     * @param blockNumberX The block number in the x dimension (offset: multiply with
     *            <code>data.length</code>).
     * @param blockNumberY The block number in the y dimension (offset: multiply with
     *            <code>data[0.length</code>).
     */
    public void writeFloatMatrixBlock(final String objectPath, final float[][] data,
            final long blockNumberX, final long blockNumberY)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        final long[] blockDimensions = new long[]
            { data.length, data[0].length };
        writeBlockRankN(objectPath, H5T_IEEE_F32LE, H5T_NATIVE_FLOAT, HDF5.NO_DEFLATION, data,
                blockDimensions, blockDimensions, new long[]
                    { blockNumberX * data.length, blockNumberY * data[0].length });
    }

    /**
     * Writes out a block of a <code>float</code> matrix (array of rank 2). The data set needs to
     * have been created by {@link #createFloatMatrix(String, long, long, int, int, boolean)}
     * beforehand.
     * <p>
     * Use this method instead of {@link #writeFloatMatrixBlock(String, float[][], long, long)} if
     * the total size of the data set is not a multiple of the block size.
     * <p>
     * <i>Note:</i> For best performance, the typical <var>dataSize</var> in this method should be
     * chosen to be equal to the <var>blockSize</var> argument of the
     * {@link #createFloatMatrix(String, long, long, int, int, boolean)} call that was used to
     * create the data set.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write.
     * @param offsetX The x offset in the data set to start writing to.
     * @param offsetY The y offset in the data set to start writing to.
     */
    public void writeFloatMatrixBlockWithOffset(final String objectPath, final float[][] data,
            final long offsetX, final long offsetY)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        final long[] blockDimensions = new long[]
            { data.length, data[0].length };
        writeBlockRankN(objectPath, H5T_IEEE_F32LE, H5T_NATIVE_FLOAT, HDF5.NO_DEFLATION, data,
                blockDimensions, blockDimensions, new long[]
                    { offsetX, offsetY });
    }

    /**
     * Writes out a multi-dimensional <code>float</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     */
    public void writeFloatMDArray(final String objectPath, final MDFloatArray data)
    {
        writeFloatMDArray(objectPath, data, false);
    }

    /**
     * Writes out a multi-dimensional <code>float</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void writeFloatMDArray(final String objectPath, final MDFloatArray data,
            final boolean deflate)
    {
        assert data != null;

        checkOpen();
        writeRankN(objectPath, H5T_IEEE_F32LE, H5T_NATIVE_FLOAT, getDeflateLevel(deflate), data
                .getAsFlatArray(), data.longDimensions());
    }

    /**
     * Creates a multi-dimensional <code>float</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param dimensions The dimensions of the array.
     * @param blockDimensions The dimensions of one block (chunk) of the array.
     */
    public void createFloatMDArray(final String objectPath, final long[] dimensions,
            final int[] blockDimensions)
    {
        createFloatMDArray(objectPath, dimensions, blockDimensions, false);
    }

    /**
     * Creates a multi-dimensional <code>float</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param dimensions The dimensions of the array.
     * @param blockDimensions The dimensions of one block (chunk) of the array.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void createFloatMDArray(final String objectPath, final long[] dimensions,
            final int[] blockDimensions, final boolean deflate)
    {
        assert objectPath != null;
        assert dimensions != null;
        assert blockDimensions != null;

        checkOpen();
        writeBlockRankN(objectPath, H5T_IEEE_F32LE, H5T_NATIVE_FLOAT, getDeflateLevel(deflate),
                null, dimensions, MDArray.toLong(blockDimensions), null);
    }

    /**
     * Writes out a block of a multi-dimensional <code>float</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     * @param blockNumber The block number in each dimension (offset: multiply with the extend in
     *            the according dimension).
     */
    public void writeFloatMDArrayBlock(final String objectPath, final MDFloatArray data,
            final long[] blockNumber)
    {
        assert data != null;
        assert blockNumber != null;

        checkOpen();
        final long[] dimensions = data.longDimensions();
        assert dimensions.length == blockNumber.length;
        final long[] offset = new long[dimensions.length];
        for (int i = 0; i < offset.length; ++i)
        {
            offset[i] = blockNumber[i] * dimensions[i];
        }
        writeBlockRankN(objectPath, H5T_IEEE_F32LE, H5T_NATIVE_FLOAT, HDF5.NO_DEFLATION, data
                .getAsFlatArray(), dimensions, dimensions, offset);
    }

    /**
     * Writes out a block of a multi-dimensional <code>float</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     * @param offset The offset in the data set to start writing to in each dimension.
     */
    public void writeFloatMDArrayBlockWithOffset(final String objectPath, final MDFloatArray data,
            final long[] offset)
    {
        assert data != null;
        assert offset != null;

        checkOpen();
        final long[] dimensions = data.longDimensions();
        assert dimensions.length == offset.length;
        writeBlockRankN(objectPath, H5T_IEEE_F32LE, H5T_NATIVE_FLOAT, HDF5.NO_DEFLATION, data
                .getAsFlatArray(), dimensions, dimensions, offset);
    }

    //
    // Double
    //

    /**
     * Writes out a <code>double</code> value.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param value The value to write.
     */
    public void writeDouble(final String objectPath, final double value)
    {
        assert objectPath != null;

        checkOpen();
        writeScalar(objectPath, H5T_IEEE_F64LE, H5T_NATIVE_DOUBLE, HDFNativeData
                .doubleToByte(value));
    }

    /**
     * Creates a <code>double</code> array (of rank 1). Uses a compact storage layout. Should only
     * be used for small data sets.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param length The length of the data set to create.
     */
    public void createDoubleArrayCompact(final String objectPath, final long length)
    {
        assert objectPath != null;
        assert length > 0;

        checkOpen();
        writeRank1Compact(objectPath, H5T_IEEE_F64LE, H5T_NATIVE_DOUBLE, null, length);
    }

    /**
     * Writes out a <code>double</code> array (of rank 1). Uses a compact storage layout. Should
     * only be used for small data sets.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     */
    public void writeDoubleArrayCompact(final String objectPath, final double[] data)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        writeRank1Compact(objectPath, H5T_IEEE_F64LE, H5T_NATIVE_DOUBLE, data, data.length);
    }

    /**
     * Writes out a <code>double</code> array (of rank 1).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     */
    public void writeDoubleArray(final String objectPath, final double[] data)
    {
        writeDoubleArray(objectPath, data, false);
    }

    /**
     * Writes out a <code>double</code> array (of rank 1).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void writeDoubleArray(final String objectPath, final double[] data, boolean deflate)
    {
        assert data != null;

        checkOpen();
        writeRank1(objectPath, H5T_IEEE_F64LE, H5T_NATIVE_DOUBLE, getDeflateLevel(deflate), data,
                data.length);
    }

    /**
     * Creates a <code>double</code> array (of rank 1).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param size The size of the double vector to create. When using extendable data sets ((see
     *            {@link #dontUseExtendableDataTypes()})), then no data set smaller than this size
     *            can be created, however data sets may be larger.
     * @param blockSize The size of one block (for block-wise IO). Ignored if no extendable data
     *            sets are used (see {@link #dontUseExtendableDataTypes()}).
     */
    public void createDoubleArray(final String objectPath, final long size, final int blockSize)
    {
        assert objectPath != null;
        assert size >= 0;
        assert blockSize >= 0 && blockSize <= size;

        checkOpen();
        createDoubleArray(objectPath, size, blockSize, false);
    }

    /**
     * Creates a <code>double</code> array (of rank 1).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param size The size of the double array to create. When using extendable data sets ((see
     *            {@link #dontUseExtendableDataTypes()})), then no data set smaller than this size
     *            can be created, however data sets may be larger.
     * @param blockSize The size of one block (for block-wise IO). Ignored if no extendable data
     *            sets are used (see {@link #dontUseExtendableDataTypes()}) and
     *            <code>deflate == false</code>.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void createDoubleArray(final String objectPath, final long size, final int blockSize,
            boolean deflate)
    {
        assert objectPath != null;
        assert size >= 0;
        assert blockSize >= 0 && blockSize <= size;

        checkOpen();
        writeBlockRank1(objectPath, H5T_IEEE_F64LE, H5T_NATIVE_DOUBLE, getDeflateLevel(deflate),
                null, size, blockSize, 0);
    }

    /**
     * Writes out a block of a <code>double</code> array (of rank 1). The data set needs to have
     * been created by {@link #createDoubleArray(String, long, int, boolean)} beforehand.
     * <p>
     * <i>Note:</i> For best performance, the block size in this method should be chosen to be equal
     * to the <var>blockSize</var> argument of the
     * {@link #createDoubleArray(String, long, int, boolean)} call that was used to create the data
     * set.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. The length defines the block size. Must not be
     *            <code>null</code> or of length 0.
     * @param blockNumber The number of the block to write.
     */
    public void writeDoubleArrayBlock(final String objectPath, final double[] data,
            final long blockNumber)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        writeBlockRank1(objectPath, H5T_IEEE_F64LE, H5T_NATIVE_DOUBLE, HDF5.NO_DEFLATION, data,
                data.length, data.length, data.length * blockNumber);
    }

    /**
     * Writes out a block of a <code>double</code> array (of rank 1). The data set needs to have
     * been created by {@link #createDoubleArray(String, long, int, boolean)} beforehand.
     * <p>
     * Use this method instead of {@link #writeDoubleArrayBlock(String, double[], long)} if the
     * total size of the data set is not a multiple of the block size.
     * <p>
     * <i>Note:</i> For best performance, the typical <var>dataSize</var> in this method should be
     * chosen to be equal to the <var>blockSize</var> argument of the
     * {@link #createDoubleArray(String, long, int, boolean)} call that was used to create the data
     * set.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. The length defines the block size. Must not be
     *            <code>null</code> or of length 0.
     * @param dataSize The (real) size of <code>data</code> (needs to be <code><= data.length</code>
     *            )
     * @param offset The offset in the data set to start writing to.
     */
    public void writeDoubleArrayBlockWithOffset(final String objectPath, final double[] data,
            final int dataSize, final long offset)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        writeBlockRank1(objectPath, H5T_IEEE_F64LE, H5T_NATIVE_DOUBLE, HDF5.NO_DEFLATION, data,
                dataSize, dataSize, offset);
    }

    /**
     * Writes out a <code>double</code> matrix (array of rank 2).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     */
    public void writeDoubleMatrix(final String objectPath, final double[][] data)
    {
        writeDoubleMatrix(objectPath, data, false);
    }

    /**
     * Writes out a <code>double</code> matrix (array of rank 2).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void writeDoubleMatrix(final String objectPath, final double[][] data, boolean deflate)
    {
        assert data != null;
        assert checkDimensions(data);

        checkOpen();
        writeRankN(objectPath, H5T_IEEE_F64LE, H5T_NATIVE_DOUBLE, getDeflateLevel(deflate), data,
                new long[]
                    { data.length, data.length == 0 ? 0 : data[0].length });
    }

    /**
     * Creates a <code>double</code> matrix (array of rank 2).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param sizeX The size of the x dimension of the double matrix to create.
     * @param sizeY The size of the y dimension of the double matrix to create.
     * @param blockSizeX The size of one block in the x dimension.
     * @param blockSizeY The size of one block in the y dimension.
     */
    public void createDoubleMatrix(final String objectPath, final long sizeX, final long sizeY,
            int blockSizeX, int blockSizeY)
    {
        createDoubleMatrix(objectPath, sizeX, sizeY, blockSizeX, blockSizeY, false);
    }

    /**
     * Creates a <code>double</code> matrix (array of rank 2).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param sizeX The size of the x dimension of the double matrix to create.
     * @param sizeY The size of the y dimension of the double matrix to create.
     * @param blockSizeX The size of one block in the x dimension.
     * @param blockSizeY The size of one block in the y dimension.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void createDoubleMatrix(final String objectPath, final long sizeX, final long sizeY,
            int blockSizeX, int blockSizeY, boolean deflate)
    {
        assert objectPath != null;
        assert sizeX >= 0;
        assert sizeY >= 0;
        assert blockSizeX >= 0 && blockSizeX <= sizeX;
        assert blockSizeY >= 0 && blockSizeY <= sizeY;

        checkOpen();
        writeBlockRankN(objectPath, H5T_IEEE_F64LE, H5T_NATIVE_DOUBLE, getDeflateLevel(deflate),
                null, new long[]
                    { sizeX, sizeY }, new long[]
                    { blockSizeX, blockSizeY }, new long[]
                    { 0, 0 });
    }

    /**
     * Writes out a block of a <code>double</code> matrix (array of rank 2). The data set needs to
     * have been created by {@link #createDoubleMatrix(String, long, long, int, int, boolean)}
     * beforehand.
     * <p>
     * Use this method instead of {@link #createDoubleMatrix(String, long, long, int, int, boolean)}
     * if the total size of the data set is not a multiple of the block size.
     * <p>
     * <i>Note:</i> For best performance, the size of <var>data</var> in this method should match
     * the <var>blockSizeX/Y</var> arguments of the
     * {@link #createDoubleMatrix(String, long, long, int, int, boolean)} call that was used to
     * create the data set.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. The length defines the block size. Must not be
     *            <code>null</code> or of length 0.
     * @param blockNumberX The block number in the x dimension (offset: multiply with
     *            <code>data.length</code>).
     * @param blockNumberY The block number in the y dimension (offset: multiply with
     *            <code>data[0.length</code>).
     */
    public void writeDoubleMatrixBlock(final String objectPath, final double[][] data,
            final long blockNumberX, final long blockNumberY)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        final long[] blockDimensions = new long[]
            { data.length, data[0].length };
        writeBlockRankN(objectPath, H5T_IEEE_F64LE, H5T_NATIVE_DOUBLE, HDF5.NO_DEFLATION, data,
                blockDimensions, blockDimensions, new long[]
                    { blockNumberX * data.length, blockNumberY * data[0].length });
    }

    /**
     * Writes out a block of a <code>double</code> matrix (array of rank 2). The data set needs to
     * have been created by {@link #createDoubleMatrix(String, long, long, int, int, boolean)}
     * beforehand.
     * <p>
     * Use this method instead of {@link #writeDoubleMatrixBlock(String, double[][], long, long)} if
     * the total size of the data set is not a multiple of the block size.
     * <p>
     * <i>Note:</i> For best performance, the typical <var>dataSize</var> in this method should be
     * chosen to be equal to the <var>blockSize</var> argument of the
     * {@link #createDoubleMatrix(String, long, long, int, int, boolean)} call that was used to
     * create the data set.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write.
     * @param offsetX The x offset in the data set to start writing to.
     * @param offsetY The y offset in the data set to start writing to.
     */
    public void writeDoubleMatrixBlockWithOffset(final String objectPath, final double[][] data,
            final long offsetX, final long offsetY)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        final long[] blockDimensions = new long[]
            { data.length, data[0].length };
        writeBlockRankN(objectPath, H5T_IEEE_F64LE, H5T_NATIVE_DOUBLE, HDF5.NO_DEFLATION, data,
                blockDimensions, blockDimensions, new long[]
                    { offsetX, offsetY });
    }

    /**
     * Writes out a multi-dimensional <code>double</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     */
    public void writeDoubleMDArray(final String objectPath, final MDDoubleArray data)
    {
        writeDoubleMDArray(objectPath, data, false);
    }

    /**
     * Writes out a multi-dimensional <code>double</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void writeDoubleMDArray(final String objectPath, final MDDoubleArray data,
            final boolean deflate)
    {
        assert data != null;

        checkOpen();
        writeRankN(objectPath, H5T_IEEE_F64LE, H5T_NATIVE_DOUBLE, getDeflateLevel(deflate), data
                .getAsFlatArray(), data.longDimensions());
    }

    /**
     * Creates a multi-dimensional <code>double</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param dimensions The dimensions of the array.
     * @param blockDimensions The dimensions of one block (chunk) of the array.
     */
    public void createDoubleMDArray(final String objectPath, final long[] dimensions,
            final int[] blockDimensions)
    {
        createDoubleMDArray(objectPath, dimensions, blockDimensions, false);
    }

    /**
     * Creates a multi-dimensional <code>double</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param dimensions The dimensions of the array.
     * @param blockDimensions The dimensions of one block (chunk) of the array.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void createDoubleMDArray(final String objectPath, final long[] dimensions,
            final int[] blockDimensions, final boolean deflate)
    {
        assert objectPath != null;
        assert dimensions != null;
        assert blockDimensions != null;

        checkOpen();
        writeBlockRankN(objectPath, H5T_IEEE_F64LE, H5T_NATIVE_DOUBLE, getDeflateLevel(deflate),
                null, dimensions, MDArray.toLong(blockDimensions), null);
    }

    /**
     * Writes out a block of a multi-dimensional <code>double</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     * @param blockNumber The block number in each dimension (offset: multiply with the extend in
     *            the according dimension).
     */
    public void writeDoubleMDArrayBlock(final String objectPath, final MDDoubleArray data,
            final long[] blockNumber)
    {
        assert data != null;
        assert blockNumber != null;

        checkOpen();
        final long[] dimensions = data.longDimensions();
        assert dimensions.length == blockNumber.length;
        final long[] offset = new long[dimensions.length];
        for (int i = 0; i < offset.length; ++i)
        {
            offset[i] = blockNumber[i] * dimensions[i];
        }
        writeBlockRankN(objectPath, H5T_IEEE_F64LE, H5T_NATIVE_DOUBLE, HDF5.NO_DEFLATION, data
                .getAsFlatArray(), dimensions, dimensions, offset);
    }

    /**
     * Writes out a block of a multi-dimensional <code>double</code> array.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>. All columns need to have the
     *            same length.
     * @param offset The offset in the data set to start writing to in each dimension.
     */
    public void writeDoubleMDArrayBlockWithOffset(final String objectPath,
            final MDDoubleArray data, final long[] offset)
    {
        assert data != null;
        assert offset != null;

        checkOpen();
        final long[] dimensions = data.longDimensions();
        assert dimensions.length == offset.length;
        writeBlockRankN(objectPath, H5T_IEEE_F64LE, H5T_NATIVE_DOUBLE, HDF5.NO_DEFLATION, data
                .getAsFlatArray(), dimensions, dimensions, offset);
    }

    // ------------------------------------------------------------------------------
    // GENERATED CODE SECTION - END
    // ------------------------------------------------------------------------------

    //
    // Date
    //

    /**
     * Writes out a time stamp value. The data set will be tagged as type variant
     * {@link HDF5DataTypeVariant#TIMESTAMP_MILLISECONDS_SINCE_START_OF_THE_EPOCH}.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param timeStamp The timestamp to write as number of milliseconds since January 1, 1970,
     *            00:00:00 GMT.
     */
    public void writeTimeStamp(final String objectPath, final long timeStamp)
    {
        assert objectPath != null;

        checkOpen();
        writeScalar(objectPath, H5T_STD_I64LE, H5T_NATIVE_INT64, HDFNativeData
                .longToByte(timeStamp));
        addTypeVariantAttribute(objectPath,
                HDF5DataTypeVariant.TIMESTAMP_MILLISECONDS_SINCE_START_OF_THE_EPOCH);
    }

    /**
     * Creates a <code>long</code> array (of rank 1). Uses a compact storage layout. Should only be
     * used for small data sets.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param length The length of the data set to create.
     */
    public void createTimeStampArrayCompact(final String objectPath, final long length)
    {
        assert objectPath != null;
        assert length > 0;

        checkOpen();
        writeRank1Compact(objectPath, H5T_STD_I64LE, H5T_NATIVE_INT64, null, length);
        addTypeVariantAttribute(objectPath,
                HDF5DataTypeVariant.TIMESTAMP_MILLISECONDS_SINCE_START_OF_THE_EPOCH);
    }

    /**
     * Writes out a <code>long</code> array (of rank 1). Uses a compact storage layout. Should only
     * be used for small data sets.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param timeStamps The timestamps to write as number of milliseconds since January 1, 1970,
     *            00:00:00 GMT.
     */
    public void writeTimeStampArrayCompact(final String objectPath, final long[] timeStamps)
    {
        assert objectPath != null;
        assert timeStamps != null;

        checkOpen();
        writeRank1Compact(objectPath, H5T_STD_I64LE, H5T_NATIVE_INT64, timeStamps,
                timeStamps.length);
        addTypeVariantAttribute(objectPath,
                HDF5DataTypeVariant.TIMESTAMP_MILLISECONDS_SINCE_START_OF_THE_EPOCH);
    }

    /**
     * Writes out a time stamp array (of rank 1). The data set will be tagged as type variant
     * {@link HDF5DataTypeVariant#TIMESTAMP_MILLISECONDS_SINCE_START_OF_THE_EPOCH}.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param timeStamps The timestamps to write as number of milliseconds since January 1, 1970,
     *            00:00:00 GMT.
     */
    public void writeTimeStampArray(final String objectPath, final long[] timeStamps)
    {
        writeTimeStampArray(objectPath, timeStamps, false);
    }

    /**
     * Writes out a time stamp array (of rank 1). The data set will be tagged as type variant
     * {@link HDF5DataTypeVariant#TIMESTAMP_MILLISECONDS_SINCE_START_OF_THE_EPOCH}.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param timeStamps The timestamps to write as number of milliseconds since January 1, 1970,
     *            00:00:00 GMT.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void writeTimeStampArray(final String objectPath, final long[] timeStamps,
            final boolean deflate)
    {
        assert objectPath != null;
        assert timeStamps != null;

        checkOpen();
        writeRank1(objectPath, H5T_STD_I64LE, H5T_NATIVE_INT64, getDeflateLevel(deflate),
                timeStamps, timeStamps.length);
        addTypeVariantAttribute(objectPath,
                HDF5DataTypeVariant.TIMESTAMP_MILLISECONDS_SINCE_START_OF_THE_EPOCH);
    }

    /**
     * Writes out a time stamp value provided as a {@link Date}. The data set will be tagged as type
     * variant {@link HDF5DataTypeVariant#TIMESTAMP_MILLISECONDS_SINCE_START_OF_THE_EPOCH}.
     * <p>
     * <em>Note: This is a convenience method for <code></code> </em>
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param date The date to write.
     */
    public void writeDate(final String objectPath, final Date date)
    {
        writeTimeStamp(objectPath, date.getTime());
    }

    /**
     * Writes out a {@link Date} array (of rank 1). Uses a compact storage layout. Should only be
     * used for small data sets.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param dates The dates to write.
     */
    public void writeDateArrayCompact(final String objectPath, final Date[] dates)
    {
        writeTimeStampArrayCompact(objectPath, datesToTimeStamps(dates));
    }

    /**
     * Writes out a {@link Date} array (of rank 1).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param dates The dates to write.
     */
    public void writeDateArray(final String objectPath, final Date[] dates)
    {
        writeTimeStampArray(objectPath, datesToTimeStamps(dates));
    }

    /**
     * Writes out a {@link Date} array (of rank 1).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param dates The dates to write.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void writeDateArray(final String objectPath, final Date[] dates, final boolean deflate)
    {
        writeTimeStampArray(objectPath, datesToTimeStamps(dates), deflate);
    }

    private long[] datesToTimeStamps(Date[] dates)
    {
        assert dates != null;

        final long[] timestamps = new long[dates.length];
        for (int i = 0; i < timestamps.length; ++i)
        {
            timestamps[i] = dates[i].getTime();
        }
        return timestamps;
    }

    //
    // String
    //

    /**
     * Writes out a <code>String</code> with a fixed maximal length.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     * @param maxLength The maximal length of the <var>data</var>.
     */
    public void writeString(final String objectPath, final String data, final int maxLength)
    {
        writeString(objectPath, data, maxLength, false);
    }

    /**
     * Writes out a <code>String</code> with a fixed maximal length (which is the length of the
     * string <var>data</var>).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     */
    public void writeString(final String objectPath, final String data)
    {
        writeString(objectPath, data, data.length(), false);
    }

    /**
     * Writes out a <code>String</code> with a fixed maximal length.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void writeString(final String objectPath, final String data, final boolean deflate)
    {
        writeString(objectPath, data, data.length(), deflate);
    }

    /**
     * Writes out a <code>String</code> with a fixed maximal length.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     * @param maxLength The maximal length of the <var>data</var>.
     * @param deflate If <code>true</code>, the data set will be compressed.
     */
    public void writeString(final String objectPath, final String data, final int maxLength,
            final boolean deflate)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        final ICallableWithCleanUp<Object> writeRunnable = new ICallableWithCleanUp<Object>()
            {
                public Object call(ICleanUpRegistry registry)
                {
                    final int stringDataTypeId = h5.createDataTypeString(maxLength + 1, registry);
                    final long[] chunkSizeOrNull =
                            HDF5Utils.tryGetChunkSizeForString(maxLength, deflate);
                    final int dataSetId;
                    if (exists(objectPath))
                    {
                        dataSetId = h5.openDataSet(fileId, objectPath, registry);
                    } else
                    {
                        final StorageLayout layout =
                                determineLayout(stringDataTypeId, HDF5Utils.SCALAR_DIMENSIONS,
                                        chunkSizeOrNull, false);
                        dataSetId =
                                h5.createDataSet(fileId, HDF5Utils.SCALAR_DIMENSIONS,
                                        chunkSizeOrNull, stringDataTypeId,
                                        getDeflateLevel(deflate), objectPath, layout, registry);
                    }
                    h5.writeDataSet(dataSetId, stringDataTypeId, (data + '\0').getBytes());
                    return null; // Nothing to return.
                }

            };
        runner.call(writeRunnable);
    }

    /**
     * Writes out a <code>String</code> array (of rank 1).
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     * @param deflate If <code>true</code>, the data will be stored compressed.
     */
    public void writeStringArray(final String objectPath, final String[] data, final boolean deflate)
    {
        assert objectPath != null;
        assert data != null;

        writeStringArray(objectPath, data, getMaxLength(data), deflate);
    }

    /**
     * Writes out a <code>String</code> array (of rank 1). Each element of the array will have a
     * fixed maximal length which is defined by the longest string in <var>data</var>.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     */
    public void writeStringArray(final String objectPath, final String[] data)
    {
        assert objectPath != null;
        assert data != null;

        writeStringArray(objectPath, data, getMaxLength(data), false);
    }

    /**
     * Writes out a <code>String</code> array (of rank 1). Each element of the array will have a
     * fixed maximal length which is given by <var>maxLength</var>.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     * @param maxLength The maximal length of any of the strings in <var>data</var>.
     */
    public void writeStringArray(final String objectPath, final String[] data, final int maxLength)
    {
        writeStringArray(objectPath, data, maxLength, false);
    }

    private static int getMaxLength(String[] data)
    {
        int maxLength = 0;
        for (String s : data)
        {
            maxLength = Math.max(maxLength, s.length());
        }
        return maxLength;
    }

    /**
     * Writes out a <code>String</code> array (of rank 1). Each element of the array will have a
     * fixed maximal length which is given by <var>maxLength</var>.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     * @param maxLength The maximal length of any of the strings in <var>data</var>.
     * @param deflate If <code>true</code>, the data will be stored compressed.
     */
    public void writeStringArray(final String objectPath, final String[] data, final int maxLength,
            final boolean deflate)
    {
        assert objectPath != null;
        assert data != null;
        assert maxLength > 0;

        checkOpen();
        final ICallableWithCleanUp<Object> writeRunnable = new ICallableWithCleanUp<Object>()
            {
                public Object call(ICleanUpRegistry registry)
                {
                    final int stringDataTypeId = h5.createDataTypeString(maxLength + 1, registry);
                    final int dataSetId;
                    final long[] dimensions = new long[]
                        { data.length };
                    if (exists(objectPath))
                    {
                        dataSetId = h5.openDataSet(fileId, objectPath, registry);
                        // Implementation note: HDF5 1.8 seems to be able to change the size even if
                        // dimensions are not in bound of max dimensions, but the resulting file can
                        // no longer be read correctly by a HDF5 1.6.x library.
                        if (dimensionsInBounds(dataSetId, dimensions))
                        {
                            h5.setDataSetExtent(dataSetId, dimensions);
                        }
                    } else
                    {
                        final long[] chunkSizeOrNull =
                                HDF5Utils.tryGetChunkSizeForStringVector(data.length, maxLength,
                                        deflate, useExtentableDataTypes);
                        final StorageLayout layout =
                                determineLayout(stringDataTypeId, dimensions, chunkSizeOrNull,
                                        false);
                        dataSetId =
                                h5.createDataSet(fileId, dimensions, chunkSizeOrNull,
                                        stringDataTypeId, getDeflateLevel(deflate), objectPath,
                                        layout, registry);
                    }
                    h5.writeDataSet(dataSetId, stringDataTypeId, data, maxLength);
                    return null; // Nothing to return.
                }
            };
        runner.call(writeRunnable);
    }

    /**
     * Writes out a <code>String</code> with variable maximal length.
     * <p>
     * The advantage of this method over {@link #writeString(String, String)} is that when writing a
     * new string later it can have a different (also greater) length. The disadvantage is that it
     * it is more time consuming to read and write this kind of string and that it can't be
     * compressed.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write. Must not be <code>null</code>.
     */
    public void writeStringVariableLength(final String objectPath, final String data)
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        final ICallableWithCleanUp<Object> writeRunnable = new ICallableWithCleanUp<Object>()
            {
                public Object call(ICleanUpRegistry registry)
                {
                    final int dataSetId =
                            h5.createScalarDataSet(fileId, variableLengthStringDataTypeId,
                                    objectPath, registry);
                    h5.writeDataSet(dataSetId, variableLengthStringDataTypeId, new String[]
                        { data });
                    return null; // Nothing to return.
                }
            };
        runner.call(writeRunnable);
    }

    //
    // Enum
    //

    /**
     * Returns the enumeration type <var>name</var> for this HDF5 file, if necessary creating it. If
     * it does already exist, the values of the type will be checked against <var>values</var>.
     * 
     * @param name The name of the enumeration in the HDF5 file.
     * @param values The values of the enumeration.
     * @throws HDF5JavaException If the data type exists and is not compatible with the
     *             <var>values</var> provided.
     */
    @Override
    public HDF5EnumerationType getEnumType(final String name, final String[] values)
            throws HDF5JavaException
    {
        return getEnumType(name, values, true);
    }

    /**
     * Returns the enumeration type <var>name</var> for this HDF5 file, if necessary creating it.
     * 
     * @param name The name of the enumeration in the HDF5 file.
     * @param values The values of the enumeration.
     * @param check If <code>true</code> and if the data type already exists, check whether it is
     *            compatible with the <var>values</var> provided.
     * @throws HDF5JavaException If <code>check = true</code>, the data type exists and is not
     *             compatible with the <var>values</var> provided.
     */
    @Override
    public HDF5EnumerationType getEnumType(final String name, final String[] values,
            final boolean check) throws HDF5JavaException
    {
        checkOpen();
        final String dataTypePath = HDF5Utils.createDataTypePath(HDF5Utils.ENUM_PREFIX, name);
        int storageDataTypeId = getDataTypeId(dataTypePath);
        if (storageDataTypeId < 0)
        {
            storageDataTypeId = h5.createDataTypeEnum(values, fileRegistry);
            commitDataType(dataTypePath, storageDataTypeId);
        } else if (check)
        {
            checkEnumValues(storageDataTypeId, values, name);
        }
        final int nativeDataTypeId = h5.getNativeDataType(storageDataTypeId, fileRegistry);
        return new HDF5EnumerationType(fileId, storageDataTypeId, nativeDataTypeId, name, values);
    }

    /**
     * Writes out an enum value.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param value The value of the data set.
     * @throws HDF5JavaException If the enum type of <var>value</var> is not a type of this file.
     */
    public void writeEnum(final String objectPath, final HDF5EnumerationValue value)
            throws HDF5JavaException
    {
        assert objectPath != null;
        assert value != null;

        checkOpen();
        value.getType().check(fileId);
        final int storageDataTypeId = value.getType().getStorageTypeId();
        final int nativeDataTypeId = value.getType().getNativeTypeId();
        writeScalar(objectPath, storageDataTypeId, nativeDataTypeId, value.toStorageForm());
    }

    /**
     * Writes out an array of enum values. Uses a compact storage layout. Must only be used for
     * small data sets.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write.
     * @throws HDF5JavaException If the enum type of <var>value</var> is not a type of this file.
     */
    public void writeEnumArrayCompact(final String objectPath, final HDF5EnumerationValueArray data)
            throws HDF5JavaException
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        data.getType().check(fileId);
        final int storageDataTypeId = data.getType().getStorageTypeId();
        final int nativeDataTypeId = data.getType().getNativeTypeId();
        writeRank1Compact(objectPath, storageDataTypeId, nativeDataTypeId, data.getStorageForm(),
                data.getLength());
    }

    /**
     * Writes out an array of enum values.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param data The data to write.
     * @param deflate If <code>true</code>, the data will be stored compressed.
     * @throws HDF5JavaException If the enum type of <var>value</var> is not a type of this file.
     */
    public void writeEnumArray(final String objectPath, final HDF5EnumerationValueArray data,
            final boolean deflate) throws HDF5JavaException
    {
        assert objectPath != null;
        assert data != null;

        checkOpen();
        data.getType().check(fileId);
        final int storageDataTypeId = data.getType().getStorageTypeId();
        final int nativeDataTypeId = data.getType().getNativeTypeId();
        writeRank1(objectPath, storageDataTypeId, nativeDataTypeId, getDeflateLevel(deflate), data
                .getStorageForm(), data.getLength());
    }

    //
    // Compound
    //

    /**
     * Returns the compound type <var>name></var> for this HDF5 file, if necessary creating it.
     * 
     * @param name The name of the compound in the HDF5 file.
     * @param compoundType The Java type that corresponds to this HDF5 type.
     * @param members The mapping from the Java compound type to the HDF5 type.
     */
    @Override
    public <T> HDF5CompoundType<T> getCompoundType(final String name, Class<T> compoundType,
            HDF5CompoundMemberMapping... members)
    {
        checkOpen();
        final HDF5ValueObjectByteifyer<T> objectByteifyer = createByteifyers(compoundType, members);
        final String dataTypeName = (name != null) ? name : compoundType.getSimpleName();
        final int storageDataTypeId =
                getOrCreateCompoundDataType(dataTypeName, compoundType, objectByteifyer);
        final int nativeDataTypeId = createNativeCompoundDataType(objectByteifyer);
        return new HDF5CompoundType<T>(fileId, storageDataTypeId, nativeDataTypeId, dataTypeName,
                compoundType, objectByteifyer);
    }

    /**
     * Returns the compound type <var>name></var> for this HDF5 file, if necessary creating it.
     * 
     * @param compoundType The Java type that corresponds to this HDF5 type.
     * @param members The mapping from the Java compound type to the HDF5 type.
     */
    @Override
    public <T> HDF5CompoundType<T> getCompoundType(Class<T> compoundType,
            HDF5CompoundMemberMapping... members)
    {
        return getCompoundType(null, compoundType, members);
    }

    private <T> int getOrCreateCompoundDataType(final String dataTypeName,
            final Class<T> compoundClass, final HDF5ValueObjectByteifyer<T> objectByteifyer)
    {
        final String dataTypePath =
                HDF5Utils.createDataTypePath(HDF5Utils.COMPOUND_PREFIX, dataTypeName);
        int storageDataTypeId = getDataTypeId(dataTypePath);
        if (storageDataTypeId < 0)
        {
            storageDataTypeId = createStorageCompoundDataType(objectByteifyer);
            commitDataType(dataTypePath, storageDataTypeId);
        }
        return storageDataTypeId;
    }

    /**
     * Writes out an array (of rank 1) of compound values. Uses a compact storage layout. Must only
     * be used for small data sets.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param type The type definition of this compound type.
     * @param data The value of the data set.
     * @param deflate If <code>true</code>, the data will be stored compressed.
     */
    public <T> void writeCompound(final String objectPath, final HDF5CompoundType<T> type,
            final T data, final boolean deflate)
    {
        checkOpen();
        type.check(fileId);
        writeScalar(objectPath, type.getStorageTypeId(), type.getNativeTypeId(), type
                .getObjectByteifyer().byteify(type.getStorageTypeId(), data));
    }

    /**
     * Writes out an array (of rank 1) of compound values. Uses a compact storage layout. Must only
     * be used for small data sets.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param type The type definition of this compound type.
     * @param data The value of the data set.
     */
    public <T> void writeCompound(final String objectPath, final HDF5CompoundType<T> type,
            final T data)
    {
        writeCompound(objectPath, type, data, false);
    }

    /**
     * Writes out an array (of rank 1) of compound values. Uses a compact storage layout. Must only
     * be used for small data sets.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param type The type definition of this compound type.
     * @param data The value of the data set.
     * @param deflate If <code>true</code>, the data will be stored compressed.
     */
    public <T> void writeCompoundArrayCompact(final String objectPath,
            final HDF5CompoundType<T> type, final T[] data, final boolean deflate)
    {
        checkOpen();
        type.check(fileId);
        writeCompoundArray(objectPath, type, new long[]
            { data.length }, null, null, data, deflate, true);
    }

    /**
     * Writes out an array (of rank 1) of compound values. Uses a compact storage layout. Must only
     * be used for small data sets.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param type The type definition of this compound type.
     * @param data The value of the data set.
     */
    public <T> void writeCompoundArrayCompact(final String objectPath,
            final HDF5CompoundType<T> type, final T[] data)
    {
        checkOpen();
        type.check(fileId);
        writeCompoundArray(objectPath, type, new long[]
            { data.length }, null, null, data, false, true);
    }

    /**
     * Writes out an array (of rank 1) of compound values.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param type The type definition of this compound type.
     * @param data The value of the data set.
     * @param deflate If <code>true</code>, the data will be stored compressed.
     */
    public <T> void writeCompoundArray(final String objectPath, final HDF5CompoundType<T> type,
            final T[] data, final boolean deflate)
    {
        checkOpen();
        type.check(fileId);
        writeCompoundArray(objectPath, type, new long[]
            { data.length }, null, null, data, deflate, false);
    }

    /**
     * Writes out an array (of rank 1) of compound values.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param type The type definition of this compound type.
     * @param data The value of the data set.
     */
    public <T> void writeCompoundArray(final String objectPath, final HDF5CompoundType<T> type,
            final T[] data)
    {
        checkOpen();
        type.check(fileId);
        writeCompoundArray(objectPath, type, new long[]
            { data.length }, null, null, data, false, false);
    }

    /**
     * Writes out a block <var>blockNumber</var> of an array (of rank 1) of compound values.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param type The type definition of this compound type.
     * @param data The value of the data set.
     * @param blockNumber The number of the block to write.
     */
    public <T> void writeCompoundArrayBlock(final String objectPath,
            final HDF5CompoundType<T> type, final T[] data, final long blockNumber)
    {
        checkOpen();
        type.check(fileId);
        final long size = data.length;
        final long[] dimensions = new long[]
            { size };
        final long[] offset = new long[]
            { size * blockNumber };
        writeCompoundArray(objectPath, type, dimensions, dimensions, offset, data, false, false);
    }

    /**
     * Writes out a block of an array (of rank 1) of compound values with given <var>offset</var>.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param type The type definition of this compound type.
     * @param data The value of the data set.
     * @param offset The offset of the block in the data set.
     */
    public <T> void writeCompoundArrayBlockWithOffset(final String objectPath,
            final HDF5CompoundType<T> type, final T[] data, final long offset)
    {
        checkOpen();
        type.check(fileId);
        final long size = data.length;
        final long[] dimensions = new long[]
            { size };
        final long[] offsetArray = new long[]
            { offset };
        writeCompoundArray(objectPath, type, dimensions, dimensions, offsetArray, data, false,
                false);
    }

    /**
     * Creates an array (of rank 1) of compound values.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param type The type definition of this compound type.
     * @param size The size of the compound array to create.
     * @param blockSize The size of one block (for block-wise IO). Ignored if no extendable data
     *            sets are used (see {@link #dontUseExtendableDataTypes()}) and
     *            <code>deflate == false</code>.
     * @param deflate If <code>true</code>, the data will be stored compressed.
     */
    public <T> void createCompoundArray(final String objectPath, final HDF5CompoundType<T> type,
            final long size, final int blockSize, final boolean deflate)
    {
        checkOpen();
        type.check(fileId);
        writeCompoundArray(objectPath, type, new long[]
            { size }, new long[]
            { blockSize }, null, null, deflate, false);
    }

    /**
     * Creates an array (of rank 1) of compound values.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param type The type definition of this compound type.
     * @param size The size of the compound array to create.
     * @param blockSize The size of one block (for block-wise IO). Ignored if no extendable data
     *            sets are used (see {@link #dontUseExtendableDataTypes()}) and
     *            <code>deflate == false</code>.
     */
    public <T> void createCompoundArray(final String objectPath, final HDF5CompoundType<T> type,
            final long size, final int blockSize)
    {
        checkOpen();
        type.check(fileId);
        writeCompoundArray(objectPath, type, new long[]
            { size }, new long[]
            { blockSize }, null, null, false, false);
    }

    /**
     * Writes out an array (of rank N) of compound values.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param type The type definition of this compound type.
     * @param data The data to write.
     * @param deflate If <code>true</code>, the data will be stored compressed.
     */
    public <T> void writeCompoundMDArray(final String objectPath, final HDF5CompoundType<T> type,
            final MDArray<T> data, final boolean deflate)
    {
        checkOpen();
        type.check(fileId);
        writeCompoundArray(objectPath, type, MDArray.toLong(data.dimensions()), null, null, data
                .getAsFlatArray(), deflate, false);
    }

    /**
     * Writes out a block of an array (of rank N) of compound values.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param type The type definition of this compound type.
     * @param data The data to write.
     * @param blockDimensions The extent of the block to write on each axis.
     */
    public <T> void writeCompoundMDArrayBlock(final String objectPath,
            final HDF5CompoundType<T> type, final MDArray<T> data, final long[] blockDimensions)
    {
        checkOpen();
        type.check(fileId);
        final long[] dimensions = data.longDimensions();
        final long[] offset = new long[dimensions.length];
        for (int i = 0; i < offset.length; ++i)
        {
            offset[i] = blockDimensions[i] * dimensions[i];
        }
        writeCompoundArray(objectPath, type, dimensions, dimensions, offset, data.getAsFlatArray(),
                false, false);
    }

    /**
     * Writes out a block of an array (of rank N) of compound values give a given <var>offset</var>.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param type The type definition of this compound type.
     * @param data The data to write.
     * @param offset The offset of the block to write on each axis.
     */
    public <T> void writeCompoundMDArrayBlockWithOffset(final String objectPath,
            final HDF5CompoundType<T> type, final MDArray<T> data, final long[] offset)
    {
        checkOpen();
        type.check(fileId);
        final long[] dimensions = data.longDimensions();
        writeCompoundArray(objectPath, type, dimensions, dimensions, offset, data.getAsFlatArray(),
                false, false);
    }

    /**
     * Creates an array (of rank 1) of compound values.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param type The type definition of this compound type.
     * @param dimensions The extent of the compound array along each of the axis.
     * @param blockDimensions The extent of one block along each of the axis. (for block-wise IO).
     *            Ignored if no extendable data sets are used (see
     *            {@link #dontUseExtendableDataTypes()}) and <code>deflate == false</code>.
     * @param deflate If <code>true</code>, the data will be stored compressed.
     */
    public <T> void createCompoundMDArray(final String objectPath, final HDF5CompoundType<T> type,
            final long[] dimensions, final int[] blockDimensions, final boolean deflate)
    {
        checkOpen();
        type.check(fileId);
        writeCompoundArray(objectPath, type, dimensions, MDArray.toLong(blockDimensions), null,
                null, deflate, false);
    }

    /**
     * Writes out an array (of rank 1) of compound values.
     * 
     * @param objectPath The name (including path information) of the data set object in the file.
     * @param type The type definition of this compound type.
     * @param dimensions The dimensions of the array. Needs to match <code>dataOrNull.length</code>,
     *            if <var>dataOrNull</var> is not <code>null</code>.
     * @param blockDimensions The dimensions of the array. Needs to match
     *            <code>dataOrNull.length</code>, if <var>dataOrNull</var> is not <code>null</code>.
     * @param slabStartOrNull The offset of the block to write.
     * @param dataOrNull The value of the data set, or <code>null</code>.
     * @param deflate If <code>true</code>, the data will be stored compressed.
     * @param enforceCompactLayout If <code>true</code>, a compact storage layout will be enforced.
     */
    private <T> void writeCompoundArray(final String objectPath, final HDF5CompoundType<T> type,
            final long[] dimensions, final long[] blockDimensionsOrNull,
            final long[] slabStartOrNull, final T[] dataOrNull, final boolean deflate,
            final boolean enforceCompactLayout)
    {
        final ICallableWithCleanUp<Void> writeRunnable = new ICallableWithCleanUp<Void>()
            {
                public Void call(final ICleanUpRegistry registry)
                {
                    final int storageDataTypeId = type.getStorageTypeId();
                    final int nativeDataTypeId = type.getNativeTypeId();
                    final byte[] byteArray =
                            (dataOrNull != null) ? type.getObjectByteifyer().byteify(
                                    storageDataTypeId, dataOrNull) : null;
                    primWrite(objectPath, storageDataTypeId, nativeDataTypeId,
                            getDeflateLevel(deflate), byteArray, dimensions, blockDimensionsOrNull,
                            enforceCompactLayout, slabStartOrNull, blockDimensionsOrNull, registry);
                    return null; // Nothing to return.
                }
            };
        runner.call(writeRunnable);
    }

    //
    // Internal methods for writing data sets.
    //

    private void writeRank1(final String objectPath, final int storageDataTypeId,
            final int nativeDataTypeId, final int deflateLevel, final Object data, final int length)
    {
        writeRankN(objectPath, storageDataTypeId, nativeDataTypeId, deflateLevel, data, new long[]
            { length });
    }

    private void writeRank1Compact(final String objectPath, final int storageDataTypeId,
            final int nativeDataTypeId, final Object dataOrNull, final long length)
    {
        writeRankNCompact(objectPath, storageDataTypeId, nativeDataTypeId, HDF5.NO_DEFLATION,
                dataOrNull, new long[]
                    { length });
    }

    private void writeBlockRank1(final String objectPath, final int storageDataTypeId,
            final int nativeDataTypeId, final int deflateLevel, final Object dataOrNull,
            final long size, final long blockSize, final long offset)
    {
        final long[] blockDimensions = new long[]
            { blockSize };
        write(objectPath, storageDataTypeId, nativeDataTypeId, deflateLevel, false, dataOrNull,
                new long[]
                    { size }, blockDimensions, new long[]
                    { offset }, blockDimensions);
    }

    private void writeBlockRankN(final String objectPath, final int storageDataTypeId,
            final int nativeDataTypeId, final int deflateLevel, final Object data,
            final long[] dimensions, final long[] blockDimensions, final long[] offset)
    {
        write(objectPath, storageDataTypeId, nativeDataTypeId, deflateLevel, false, data,
                dimensions, blockDimensions, offset, blockDimensions);
    }

    private void writeRankN(final String objectPath, final int storageDataTypeId,
            final int nativeDataTypeId, final int deflateLevel, final Object dataOrNull,
            final long[] shape)
    {
        write(objectPath, storageDataTypeId, nativeDataTypeId, deflateLevel, false, dataOrNull,
                shape, null, null, null);
    }

    private void writeRankNCompact(final String objectPath, final int storageDataTypeId,
            final int nativeDataTypeId, final int deflateLevel, final Object dataOrNull,
            final long[] dimensions)
    {
        write(objectPath, storageDataTypeId, nativeDataTypeId, deflateLevel, true, dataOrNull,
                dimensions, null, null, null);
    }

    private void write(final String objectPath, final int storageDataTypeId,
            final int nativeDataTypeId, final int deflateLevel, final boolean enforceCompactLayout,
            final Object dataOrNull, final long[] dimensions, final long[] chunkSizeOrNull,
            final long[] slabStartOrNull, final long[] slabCountOrNull)
    {
        assert objectPath != null;
        assert storageDataTypeId >= 0;
        assert deflateLevel >= 0;

        final ICallableWithCleanUp<Void> writeRunnable = new ICallableWithCleanUp<Void>()
            {
                public Void call(ICleanUpRegistry registry)
                {
                    primWrite(objectPath, storageDataTypeId, nativeDataTypeId, deflateLevel,
                            dataOrNull, dimensions, chunkSizeOrNull, enforceCompactLayout,
                            slabStartOrNull, slabCountOrNull, registry);
                    return null; // Nothing to return.
                }
            };
        runner.call(writeRunnable);
    }

    private void writeScalar(final String dataSetPath, final int storageDataTypeId,
            final int nativeDataTypeId, final byte[] value)
    {
        assert dataSetPath != null;
        assert storageDataTypeId >= 0;
        assert nativeDataTypeId >= 0;
        assert value != null;

        ICallableWithCleanUp<Object> writeScalarRunnable = new ICallableWithCleanUp<Object>()
            {
                public Object call(ICleanUpRegistry registry)
                {
                    final int dataSetId;
                    if (exists(dataSetPath))
                    {
                        dataSetId = h5.openObject(fileId, dataSetPath, registry);
                    } else
                    {
                        dataSetId =
                                h5.createScalarDataSet(fileId, storageDataTypeId, dataSetPath,
                                        registry);
                    }
                    h5.writeScalarDataSet(dataSetId, nativeDataTypeId, value);
                    return null; // Nothing to return.
                }
            };
        runner.call(writeScalarRunnable);
    }

    private void primWrite(final String objectPath, final int storageDataTypeId,
            final int nativeDataTypeId, final int deflateLevel, final Object dataOrNull,
            final long[] dimensions, final long[] chunkSizeOrNull,
            final boolean enforceCompactLayout, final long[] slabStartOrNull,
            final long[] slabCountOrNull, ICleanUpRegistry registry)
    {
        assert objectPath != null;
        assert storageDataTypeId >= 0;
        assert nativeDataTypeId >= 0;
        assert deflateLevel >= 0;

        final boolean blockWrite = (slabStartOrNull != null);
        final boolean blockWriteInProgress = blockWrite && (dataOrNull != null);
        final int dataSetId;
        final boolean overwriteDataSet = blockWriteInProgress || exists(objectPath);
        if (overwriteDataSet)
        {
            dataSetId = h5.openDataSet(fileId, objectPath, registry);
            // Implementation note: HDF5 1.8 seems to be able to change the size even if
            // dimensions are not in bound of max dimensions, but the resulting file can
            // no longer be read by HDF5 1.6, thus we may only do it if useLatestFileFormat == true.
            if (blockWrite == false
                    && (dimensionsInBounds(dataSetId, dimensions) || useLatestFileFormat))
            {
                h5.setDataSetExtent(dataSetId, dimensions);
                // FIXME 2008-09-15, Bernd Rinn: This is a work-around for an apparent bug in HDF5
                // 1.8.1 with contiguous data sets! Without the flush, the next
                // h5.writeDataSet() call will not overwrite the data.
                if (h5.getLayout(dataSetId, registry) == StorageLayout.CONTIGUOUS)
                {
                    h5.flushFile(fileId);
                }
            }
        } else
        {
            dataSetId =
                    createDataSet(objectPath, storageDataTypeId, deflateLevel, dimensions,
                            chunkSizeOrNull, enforceCompactLayout, registry);
        }
        if (dataOrNull != null)
        {
            final int memorySpaceId;
            final int dataSpaceId;
            if (blockWrite)
            {
                dataSpaceId = h5.getDataSpaceForDataSet(dataSetId, registry);
                h5.setHyperslabBlock(dataSpaceId, slabStartOrNull, slabCountOrNull);
                memorySpaceId = h5.createSimpleDataSpace(slabCountOrNull, registry);
            } else
            {
                memorySpaceId = HDF5Constants.H5S_ALL;
                dataSpaceId = HDF5Constants.H5S_ALL;
            }
            h5.writeDataSet(dataSetId, nativeDataTypeId, memorySpaceId, dataSpaceId, dataOrNull);
        }
    }

    private int createDataSet(final String objectPath, final int storageDataTypeId,
            final int deflateLevel, final long[] dimensions, final long[] chunkSizeOrNull,
            boolean enforceCompactLayout, ICleanUpRegistry registry)
    {
        final int dataSetId;
        final boolean deflate = (deflateLevel != HDF5.NO_DEFLATION);
        final boolean empty = isEmpty(dimensions);
        final long[] definitiveChunkSizeOrNull;
        if (empty)
        {
            definitiveChunkSizeOrNull = HDF5Utils.tryGetChunkSize(dimensions, deflate, true);
        } else if (enforceCompactLayout)
        {
            definitiveChunkSizeOrNull = null;
        } else if (chunkSizeOrNull != null)
        {
            definitiveChunkSizeOrNull = chunkSizeOrNull;
        } else
        {
            definitiveChunkSizeOrNull =
                    HDF5Utils.tryGetChunkSize(dimensions, deflate, useExtentableDataTypes);
        }
        final StorageLayout layout =
                determineLayout(storageDataTypeId, dimensions, definitiveChunkSizeOrNull,
                        enforceCompactLayout);
        dataSetId =
                h5.createDataSet(fileId, dimensions, definitiveChunkSizeOrNull, storageDataTypeId,
                        deflateLevel, objectPath, layout, registry);
        return dataSetId;
    }

    private StorageLayout determineLayout(final int storageDataTypeId, final long[] dimensions,
            final long[] chunkSizeOrNull, boolean enforceCompactLayout)
    {
        if (chunkSizeOrNull != null)
        {
            return StorageLayout.CHUNKED;
        }
        if (enforceCompactLayout
                || computeSizeForDimensions(storageDataTypeId, dimensions) < COMPACT_LAYOUT_THRESHOLD)
        {
            return StorageLayout.COMPACT;
        }
        return StorageLayout.CONTIGUOUS;
    }

    private int computeSizeForDimensions(int dataTypeId, long[] dimensions)
    {
        int size = h5.getSize(dataTypeId);
        for (long d : dimensions)
        {
            size *= d;
        }
        return size;
    }

    private boolean dimensionsInBounds(final int dataSetId, final long[] dimensions)
    {
        final long[] maxDimensions = h5.getDataMaxDimensions(dataSetId);

        if (dimensions.length != maxDimensions.length) // Actually an error condition
        {
            return false;
        }

        for (int i = 0; i < dimensions.length; ++i)
        {
            if (maxDimensions[i] != H5S_UNLIMITED && dimensions[i] > maxDimensions[i])
            {
                return false;
            }
        }
        return true;
    }

    private int getDeflateLevel(boolean deflate)
    {
        return deflate ? DEFAULT_DEFLATION : HDF5.NO_DEFLATION;
    }

    private boolean checkDimensions(Object a)
    {
        if (a.getClass().isArray() == false)
        {
            return false;
        }
        final int length = Array.getLength(a);
        if (length == 0)
        {
            return true;
        }
        final Object element = Array.get(a, 0);
        if (element.getClass().isArray())
        {
            final int elementLength = Array.getLength(element);
            for (int i = 0; i < length; ++i)
            {
                final Object o = Array.get(a, i);
                if (checkDimensions(o) == false)
                {
                    return false;
                }
                if (elementLength != Array.getLength(o))
                {
                    return false;
                }
            }
        }
        return true;
    }

}