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

import static ncsa.hdf.hdf5lib.H5.*;
import static ncsa.hdf.hdf5lib.HDF5Constants.*;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import ncsa.hdf.hdf5lib.exceptions.HDF5Exception;
import ncsa.hdf.hdf5lib.exceptions.HDF5JavaException;

import ch.systemsx.cisd.common.process.CleanUpCallable;
import ch.systemsx.cisd.common.process.CleanUpRegistry;
import ch.systemsx.cisd.common.process.ICallableWithCleanUp;
import ch.systemsx.cisd.common.process.ICleanUpRegistry;

/**
 * A wrapper around {@link ncsa.hdf.hdf5lib.H5} that handles closing of resources automatically by
 * means of registering clean-up {@link Runnable}s.
 * 
 * @author Bernd Rinn
 */
class HDF5
{

    private final CleanUpCallable runner;

    private final int dataSetCreationPropertyListCompactStorageLayout;

    private final int abortOverflowXferPropertyListID;

    private final int lcplCreateIntermediateGroups;

    public HDF5(final CleanUpRegistry fileRegistry)
    {
        this.runner = new CleanUpCallable();
        this.dataSetCreationPropertyListCompactStorageLayout =
                createDataSetCreationPropertyList(fileRegistry);
        H5Pset_layout(dataSetCreationPropertyListCompactStorageLayout, H5D_COMPACT);
        this.abortOverflowXferPropertyListID =
                createDataSetXferPropertyListAbortOverflow(fileRegistry);
        this.lcplCreateIntermediateGroups = createLinkCreationPropertyList(true, fileRegistry);

    }

    //
    // File
    //

    public int createFile(String fileName, boolean useLatestFormat, ICleanUpRegistry registry)
    {
        final int fileAccessPropertyListId =
                createFileAccessPropertyListId(useLatestFormat, registry);
        final int fileId =
                H5Fcreate(fileName, H5F_ACC_TRUNC, H5P_DEFAULT, fileAccessPropertyListId);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Fclose(fileId);
                }
            });
        return fileId;
    }

    private int createFileAccessPropertyListId(boolean useLatestFormat, ICleanUpRegistry registry)
    {
        int fileAccessPropertyListId = H5P_DEFAULT;
        if (useLatestFormat)
        {
            final int fapl = H5Pcreate(H5P_FILE_ACCESS);
            registry.registerCleanUp(new Runnable()
                {
                    public void run()
                    {
                        H5Pclose(fapl);
                    }
                });
            H5Pset_libver_bounds(fapl, H5F_LIBVER_LATEST, H5F_LIBVER_LATEST);
            fileAccessPropertyListId = fapl;
        }
        return fileAccessPropertyListId;
    }

    public int openFileReadOnly(String fileName, ICleanUpRegistry registry)
    {
        final int fileId = H5Fopen(fileName, H5F_ACC_RDONLY, H5P_DEFAULT);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Fclose(fileId);
                }
            });
        return fileId;
    }

    public int openFileReadWrite(String fileName, boolean useLatestFormat, ICleanUpRegistry registry)
    {
        final int fileAccessPropertyListId =
                createFileAccessPropertyListId(useLatestFormat, registry);
        final File f = new File(fileName);
        if (f.exists() && f.isFile() == false)
        {
            throw new HDF5Exception("An entry with name '" + fileName
                    + "' exists but is not a file.");
        }
        final int fileId = H5Fopen(fileName, H5F_ACC_RDWR, fileAccessPropertyListId);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Fclose(fileId);
                }
            });
        return fileId;
    }

    public void flushFile(int fileId)
    {
        H5Fflush(fileId, H5F_SCOPE_GLOBAL);
    }

    //
    // Object
    //

    public int openObject(int fileId, String path, ICleanUpRegistry registry)
    {
        final int groupId = H5Oopen(fileId, path, H5P_DEFAULT);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Oclose(groupId);
                }
            });
        return groupId;
    }

    public int deleteObject(int fileId, String path)
    {
        final int success = H5Gunlink(fileId, path);
        return success;
    }

    //
    // Group
    //

    public void createGroup(int fileId, String groupName)
    {
        final int groupId =
                H5Gcreate(fileId, groupName, lcplCreateIntermediateGroups, H5P_DEFAULT, H5P_DEFAULT);
        H5Gclose(groupId);
    }

    public void createOldStyleGroup(int fileId, String groupName, int sizeHint,
            ICleanUpRegistry registry)
    {
        final int gcplId = H5Pcreate(H5P_GROUP_CREATE);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Pclose(gcplId);
                }
            });
        H5Pset_local_heap_size_hint(gcplId, sizeHint);
        final int groupId =
                H5Gcreate(fileId, groupName, lcplCreateIntermediateGroups, gcplId, H5P_DEFAULT);
        H5Gclose(groupId);
    }

    public void createNewStyleGroup(int fileId, String groupName, int maxCompact, int minDense,
            ICleanUpRegistry registry)
    {
        final int gcplId = H5Pcreate(H5P_GROUP_CREATE);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Pclose(gcplId);
                }
            });
        H5Pset_link_phase_change(gcplId, maxCompact, minDense);
        final int groupId =
                H5Gcreate(fileId, groupName, lcplCreateIntermediateGroups, gcplId, H5P_DEFAULT);
        H5Gclose(groupId);
    }

    public int openGroup(int fileId, String path, ICleanUpRegistry registry)
    {
        final int groupId = H5Gopen(fileId, path, H5P_DEFAULT);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Gclose(groupId);
                }
            });
        return groupId;
    }

    public long getNumberOfGroupMembers(int fileId, String path, ICleanUpRegistry registry)
    {
        final int groupId = H5Gopen(fileId, path, H5P_DEFAULT);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Gclose(groupId);
                }
            });
        return H5Gget_nlinks(groupId);
    }

    public boolean existsAttribute(final int objectId, final String attributeName)
    {
        return H5Aexists(objectId, attributeName);
    }

    public boolean exists(final int fileId, final String linkName)
    {
        return H5Lexists(fileId, linkName);
    }

    public HDF5LinkInformation getLinkInfo(final int fileId, final String objectName,
            boolean exceptionWhenNonExistent)
    {
        if ("/".equals(objectName))
        {
            return HDF5LinkInformation.ROOT_LINK_INFO;
        }
        final String[] lname = new String[1];
        final int typeId = H5Lget_link_info(fileId, objectName, lname, exceptionWhenNonExistent);
        return HDF5LinkInformation.create(objectName, typeId, lname[0]);
    }

    public HDF5ObjectType getTypeInfo(final int fileId, final String objectName,
            boolean exceptionWhenNonExistent)
    {
        if ("/".equals(objectName))
        {
            return HDF5ObjectType.GROUP;
        }
        final int typeId = H5Lget_link_info(fileId, objectName, null, exceptionWhenNonExistent);
        return HDF5LinkInformation.objectTypeIdToObjectType(typeId);
    }

    public String[] getGroupMembers(final int fileId, final String groupName)
    {
        ICallableWithCleanUp<String[]> dataDimensionRunnable = new ICallableWithCleanUp<String[]>()
            {
                public String[] call(ICleanUpRegistry registry)
                {
                    final int groupId = openGroup(fileId, groupName, registry);
                    final long nLong = H5Gget_nlinks(groupId);
                    final int n = (int) nLong;
                    if (n != nLong)
                    {
                        throw new RuntimeException("Number of group members is too large (n="
                                + nLong + ")");
                    }
                    final String[] names = new String[n];
                    H5Lget_link_names_all(groupId, ".", names);
                    return names;
                }
            };
        return runner.call(dataDimensionRunnable);
    }

    public List<HDF5LinkInformation> getGroupMemberLinkInfo(final int fileId,
            final String groupName, final boolean includeInternal)
    {
        ICallableWithCleanUp<List<HDF5LinkInformation>> dataDimensionRunnable =
                new ICallableWithCleanUp<List<HDF5LinkInformation>>()
                    {
                        public List<HDF5LinkInformation> call(ICleanUpRegistry registry)
                        {
                            final int groupId = openGroup(fileId, groupName, registry);
                            final long nLong = H5Gget_nlinks(groupId);
                            final int n = (int) nLong;
                            if (n != nLong)
                            {
                                throw new RuntimeException(
                                        "Number of group members is too large (n=" + nLong + ")");
                            }
                            final String[] names = new String[n];
                            final String[] linkNames = new String[n];
                            final int[] types = new int[n];
                            H5Lget_link_info_all(groupId, ".", names, types, linkNames);
                            final String superGroupName =
                                    (groupName.equals("/") ? "/" : groupName + "/");
                            final List<HDF5LinkInformation> info =
                                    new LinkedList<HDF5LinkInformation>();
                            for (int i = 0; i < n; ++i)
                            {
                                if (includeInternal || HDF5Utils.isInternalName(names[i]) == false)
                                {
                                    info.add(HDF5LinkInformation.create(superGroupName + names[i],
                                            types[i], linkNames[i]));
                                }
                            }
                            return info;
                        }
                    };
        return runner.call(dataDimensionRunnable);
    }

    public List<HDF5LinkInformation> getGroupMemberTypeInfo(final int fileId,
            final String groupName, final boolean includeInternal)
    {
        ICallableWithCleanUp<List<HDF5LinkInformation>> dataDimensionRunnable =
                new ICallableWithCleanUp<List<HDF5LinkInformation>>()
                    {
                        public List<HDF5LinkInformation> call(ICleanUpRegistry registry)
                        {
                            final int groupId = openGroup(fileId, groupName, registry);
                            final long nLong = H5Gget_nlinks(groupId);
                            final int n = (int) nLong;
                            if (n != nLong)
                            {
                                throw new RuntimeException(
                                        "Number of group members is too large (n=" + nLong + ")");
                            }
                            final String[] names = new String[n];
                            final int[] types = new int[n];
                            H5Lget_link_info_all(groupId, ".", names, types, null);
                            final String superGroupName =
                                    (groupName.equals("/") ? "/" : groupName + "/");
                            final List<HDF5LinkInformation> info =
                                    new LinkedList<HDF5LinkInformation>();
                            for (int i = 0; i < n; ++i)
                            {
                                if (includeInternal || HDF5Utils.isInternalName(names[i]) == false)
                                {
                                    info.add(HDF5LinkInformation.create(superGroupName + names[i],
                                            types[i], null));
                                }
                            }
                            return info;
                        }
                    };
        return runner.call(dataDimensionRunnable);
    }

    //
    // Link
    //

    public void createHardLink(int fileId, String objectName, String linkName)
    {
        H5Lcreate_hard(fileId, objectName, fileId, linkName, lcplCreateIntermediateGroups,
                H5P_DEFAULT);
    }

    public void createSoftLink(int fileId, String linkName, String targetPath)
    {
        H5Lcreate_soft(targetPath, fileId, linkName, lcplCreateIntermediateGroups, H5P_DEFAULT);
    }

    public void createExternalLink(int fileId, String linkName, String targetFileName,
            String targetPath)
    {
        H5Lcreate_external(targetFileName, targetPath, fileId, linkName,
                lcplCreateIntermediateGroups, H5P_DEFAULT);
    }

    //
    // Data Set
    //

    /**
     * A constant that specifies that no deflation (gzip compression) should be performed.
     */
    final static int NO_DEFLATION = 0;

    enum StorageLayout
    {
        COMPACT, CONTIGUOUS, CHUNKED
    }

    public int createDataSet(int fileId, long[] dimensions, long[] chunkSizeOrNull, int dataTypeId,
            int deflateLevel, String dataSetName, StorageLayout layout, ICleanUpRegistry registry)
    {
        final int dataSpaceId =
                H5Screate_simple(dimensions.length, dimensions, createMaxDimensions(
                        dimensions, (layout == StorageLayout.CHUNKED)));
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Sclose(dataSpaceId);
                }
            });
        final int dataSetCreationPropertyListId;
        if (layout == StorageLayout.CHUNKED && chunkSizeOrNull != null)
        {
            dataSetCreationPropertyListId = createDataSetCreationPropertyList(registry);
            setChunkedLayout(dataSetCreationPropertyListId, chunkSizeOrNull);
            if (deflateLevel != NO_DEFLATION)
            {
                setDeflate(dataSetCreationPropertyListId, deflateLevel);
            }
        } else if (layout == StorageLayout.COMPACT)
        {
            dataSetCreationPropertyListId = dataSetCreationPropertyListCompactStorageLayout;
        } else
        {
            dataSetCreationPropertyListId = H5P_DEFAULT;
        }
        final int dataSetId =
                H5Dcreate(fileId, dataSetName, dataTypeId, dataSpaceId,
                        lcplCreateIntermediateGroups, dataSetCreationPropertyListId, H5P_DEFAULT);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Dclose(dataSetId);
                }
            });

        return dataSetId;
    }

    private int createDataSetCreationPropertyList(ICleanUpRegistry registry)
    {
        final int dataSetCreationPropertyListId = H5Pcreate(H5P_DATASET_CREATE);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Pclose(dataSetCreationPropertyListId);
                }
            });
        return dataSetCreationPropertyListId;
    }

    /**
     * Returns one of: COMPACT, CHUNKED, CONTIGUOUS.
     */
    public StorageLayout getLayout(int dataSetId, ICleanUpRegistry registry)
    {
        final int dataSetCreationPropertyListId = H5Dget_create_plist(dataSetId);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Pclose(dataSetCreationPropertyListId);
                }
            });
        final int layoutId = H5Pget_layout(dataSetCreationPropertyListId);
        if (layoutId == H5D_COMPACT)
        {
            return StorageLayout.COMPACT;
        } else if (layoutId == H5D_CHUNKED)
        {
            return StorageLayout.CHUNKED;
        } else
        {
            return StorageLayout.CONTIGUOUS;
        }
    }

    private static final long[] createMaxDimensions(long[] dimensions, boolean unlimited)
    {
        if (unlimited == false)
        {
            return dimensions;
        }
        final long[] maxDimensions = new long[dimensions.length];
        Arrays.fill(maxDimensions, H5S_UNLIMITED);
        return maxDimensions;
    }

    private void setChunkedLayout(int dscpId, long[] chunkSize)
    {
        assert dscpId >= 0;

        H5Pset_layout(dscpId, H5D_CHUNKED);
        H5Pset_chunk(dscpId, chunkSize.length, chunkSize);
    }

    private void setDeflate(int dscpId, int deflateLevel)
    {
        assert dscpId >= 0;
        assert deflateLevel >= 0;

        H5Pset_deflate(dscpId, deflateLevel);
    }

    public int createScalarDataSet(int fileId, int dataTypeId, String dataSetName,
            ICleanUpRegistry registry)
    {
        final int dataSpaceId = H5Screate(H5S_SCALAR);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Sclose(dataSpaceId);
                }
            });
        final int dataSetId =
                H5Dcreate(fileId, dataSetName, dataTypeId, dataSpaceId,
                        lcplCreateIntermediateGroups,
                        dataSetCreationPropertyListCompactStorageLayout, H5P_DEFAULT);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Dclose(dataSetId);
                }
            });
        return dataSetId;
    }

    public int openDataSet(int fileId, String path, ICleanUpRegistry registry)
    {
        final int dataSetId = H5Dopen(fileId, path, H5P_DEFAULT);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Dclose(dataSetId);
                }
            });
        return dataSetId;
    }

    public void setDataSetExtent(int dataSetId, long[] dimensions)
    {
        assert dataSetId >= 0;
        assert dimensions != null;

        H5Dset_extent(dataSetId, dimensions);
    }

    public void readDataSetNonNumeric(int dataSetId, int nativeDataTypeId, Object data)
    {
        readDataSet(dataSetId, nativeDataTypeId, H5S_ALL, H5S_ALL, H5P_DEFAULT, data);
    }

    public void readDataSet(int dataSetId, int nativeDataTypeId, Object data)
    {
        readDataSet(dataSetId, nativeDataTypeId, H5S_ALL, H5S_ALL, abortOverflowXferPropertyListID, data);
    }

    public void readDataSet(int dataSetId, int nativeDataTypeId, int memorySpaceId, int fileSpaceId,
            Object data)
    {
        H5Dread(dataSetId, nativeDataTypeId, memorySpaceId, fileSpaceId, abortOverflowXferPropertyListID,
                data);
    }

    private void readDataSet(int dataSetId, int nativeDataTypeId, int memorySpaceId, int fileSpaceId,
            int xferPlistId, Object data)
    {
        H5Dread(dataSetId, nativeDataTypeId, memorySpaceId, fileSpaceId, xferPlistId, data);
    }

    public void readDataSetVL(int dataSetId, int dataTypeId, Object[] data)
    {
        H5DreadVL(dataSetId, dataTypeId, H5S_ALL, H5S_ALL, H5P_DEFAULT, data);
    }

    public void writeScalarDataSet(int dataSetId, int dataTypeId, byte[] data)
    {
        H5Dwrite(dataSetId, dataTypeId, H5S_SCALAR, H5S_SCALAR, H5P_DEFAULT, data);
    }

    public void writeDataSet(int dataSetId, int dataTypeId, Object data)
    {
        writeDataSet(dataSetId, dataTypeId, H5S_ALL, H5S_ALL, data);
    }

    public void writeDataSet(int dataSetId, int dataTypeId, int memorySpaceId, int fileSpaceId,
            Object data)
    {
        H5Dwrite(dataSetId, dataTypeId, memorySpaceId, fileSpaceId, H5P_DEFAULT, data);
    }

    public void writeDataSet(int dataSetId, int dataTypeId, String[] data, int maxLength)
    {
        H5Dwrite(dataSetId, dataTypeId, H5S_ALL, H5S_ALL, H5P_DEFAULT, data, maxLength);
    }

    //
    // Attribute
    //

    public int createAttribute(int locationId, String attributeName, int dataTypeId,
            ICleanUpRegistry registry)
    {
        final int dataSpaceId = H5Screate(H5S_SCALAR);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Sclose(dataSpaceId);
                }
            });
        final int attributeId =
                H5Acreate(locationId, attributeName, dataTypeId, dataSpaceId, H5P_DEFAULT,
                        H5P_DEFAULT);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Aclose(attributeId);
                }
            });
        return attributeId;
    }

    public int deleteAttribute(int locationId, String attributeName)
    {
        final int success = H5Adelete(locationId, attributeName);
        return success;
    }

    public int openAttribute(int locationId, String attributeName, ICleanUpRegistry registry)
    {
        final int attributeId = H5Aopen_name(locationId, attributeName);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Aclose(attributeId);
                }
            });
        return attributeId;
    }

    public List<String> getAttributeNames(int locationId, ICleanUpRegistry registry)
    {
        final int numberOfAttributes = H5Aget_num_attrs(locationId);
        final List<String> attributeNames = new LinkedList<String>();
        for (int i = 0; i < numberOfAttributes; ++i)
        {
            final int attributeId = H5Aopen_idx(locationId, i);
            registry.registerCleanUp(new Runnable()
                {
                    public void run()
                    {
                        H5Aclose(attributeId);
                    }
                });
            final String[] nameContainer = new String[1];
            // Find out length of attribute name.
            final long nameLength = H5Aget_name(attributeId, 0L, null);
            // Read attribute name
            final long nameLengthRead = H5Aget_name(attributeId, nameLength + 1, nameContainer);
            if (nameLengthRead != nameLength)
            {
                throw new HDF5JavaException(String.format(
                        "Error reading attribute name [wrong name length "
                                + "when reading attribute %d, expected: %d, found: %d]", i,
                        nameLength, nameLengthRead));
            }
            attributeNames.add(nameContainer[0]);
        }
        return attributeNames;
    }

    public void readAttribute(int attributeId, int nativeDataTypeId, Object value)
    {
        H5Aread(attributeId, nativeDataTypeId, value);
    }

    public void writeAttribute(int attributeId, int nativeDataTypeId, Object value)
    {
        H5Awrite(attributeId, nativeDataTypeId, value);
    }

    //
    // Data Type
    //

    public int copyDataType(int dataTypeId, ICleanUpRegistry registry)
    {
        final int copiedDataTypeId = H5Tcopy(dataTypeId);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Tclose(copiedDataTypeId);
                }
            });
        return copiedDataTypeId;
    }
    
    public int createDataTypeVariableString(ICleanUpRegistry registry)
    {
        final int dataTypeId = createDataTypeStringVariableLength();
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Tclose(dataTypeId);
                }
            });
        return dataTypeId;
    }

    private int createDataTypeStringVariableLength()
    {
        int dataTypeId = H5Tcopy(H5T_C_S1);
        H5Tset_size(dataTypeId, H5T_VARIABLE);
        return dataTypeId;
    }

    public int createDataTypeString(int length, ICleanUpRegistry registry)
    {
        final int dataTypeId = createDataTypeString(length);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Tclose(dataTypeId);
                }
            });
        return dataTypeId;
    }

    private int createDataTypeString(int length)
    {
        assert length >= 0;

        int dataTypeId = H5Tcopy(H5T_C_S1);
        H5Tset_size(dataTypeId, length);
        return dataTypeId;
    }

    public int createDataTypeEnum(String[] values, ICleanUpRegistry registry)
    {
        final int baseDataTypeId =
                (values.length < Byte.MAX_VALUE) ? H5T_STD_I8LE
                        : (values.length < Short.MAX_VALUE) ? H5T_STD_I16LE : H5T_STD_I32LE;
        final int dataTypeId = H5Tenum_create(baseDataTypeId);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Tclose(dataTypeId);
                }
            });
        for (int i = 0; i < values.length; ++i)
        {
            insertMemberEnum(dataTypeId, values[i], i);
        }
        return dataTypeId;
    }

    private void insertMemberEnum(int dataTypeId, String name, int value)
    {
        assert dataTypeId >= 0;
        assert name != null;

        H5Tenum_insert(dataTypeId, name, new int[]
            { value });
    }

    /** Returns the number of members of an enum type or a compound type. */
    public int getNumberOfMembers(int dataTypeId)
    {
        return H5Tget_nmembers(dataTypeId);
    }

    /**
     * Returns the name of an enum value or compound member for the given <var>index</var>.
     * <p>
     * Must not be called on a <var>dateTypeId</var> that is not an enum or compound type.
     */
    public String getNameForEnumOrCompoundMemberIndex(int dataTypeId, int index)
    {
        return H5Tget_member_name(dataTypeId, index);
    }

    /**
     * Returns the names of an enum value or compound members.
     * <p>
     * Must not be called on a <var>dateTypeId</var> that is not an enum or compound type.
     */
    public String[] getNamesForEnumOrCompoundMembers(int dataTypeId)
    {
        final int len = getNumberOfMembers(dataTypeId);
        final String[] values = new String[len];
        for (int i = 0; i < len; ++i)
        {
            values[i] = H5Tget_member_name(dataTypeId, i);
        }
        return values;
    }

    /**
     * Returns the index of an enum value or compound member for the given <var>name</var>. Works on
     * enum and compound data types.
     */
    public int getIndexForMemberName(int dataTypeId, String name)
    {
        return H5Tget_member_index(dataTypeId, name);
    }

    /**
     * Returns the data type id for a member of a compound data type, specified by index.
     */
    public int getDataTypeForIndex(int compoundDataTypeId, int index)
    {
        return H5Tget_member_type(compoundDataTypeId, index);
    }

    /**
     * Returns the data type id for a member of a compound data type, specified by name.
     */
    public int getDataTypeForMemberName(int compoundDataTypeId, String memberName)
    {
        final int index = H5Tget_member_index(compoundDataTypeId, memberName);
        return H5Tget_member_type(compoundDataTypeId, index);
    }

    public Boolean tryGetBooleanValue(final int dataTypeId, final int intValue)
    {
        if (getClassType(dataTypeId) != H5T_ENUM)
        {
            return null;
        }
        final String value = getNameForEnumOrCompoundMemberIndex(dataTypeId, intValue);
        if ("TRUE".equalsIgnoreCase(value))
        {
            return true;
        } else if ("FALSE".equalsIgnoreCase(value))
        {
            return false;
        } else
        {
            return null;
        }
    }

    public int createDataTypeCompound(int lengthInBytes, ICleanUpRegistry registry)
    {
        final int dataTypeId = H5Tcreate(H5T_COMPOUND, lengthInBytes);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Tclose(dataTypeId);
                }
            });
        return dataTypeId;
    }

    public int createDataTypeOpaque(int lengthInBytes, String tag, ICleanUpRegistry registry)
    {
        final int dataTypeId = H5Tcreate(H5T_OPAQUE, lengthInBytes);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Tclose(dataTypeId);
                }
            });
        H5Tset_tag(dataTypeId, tag.length() > H5T_OPAQUE_TAG_MAX ? tag.substring(0,
                H5T_OPAQUE_TAG_MAX) : tag);
        return dataTypeId;
    }

    public void commitDataType(int fileId, String name, int dataTypeId)
    {
        H5Tcommit(fileId, name, dataTypeId, lcplCreateIntermediateGroups, H5P_DEFAULT, H5P_DEFAULT);
    }

    public int openDataType(int fileId, String name, ICleanUpRegistry registry)
    {
        final int dataTypeId = H5Topen(fileId, name, H5P_DEFAULT);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Tclose(dataTypeId);
                }
            });
        return dataTypeId;
    }

    public boolean dataTypesAreEqual(int dataTypeId1, int dataTypeId2)
    {
        return H5Tequal(dataTypeId1, dataTypeId2);
    }

    public int getDataTypeForDataSet(int dataSetId, ICleanUpRegistry registry)
    {
        final int dataTypeId = H5Dget_type(dataSetId);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Tclose(dataTypeId);
                }
            });
        return dataTypeId;
    }

    public int getDataTypeForAttribute(int attributeId, ICleanUpRegistry registry)
    {
        final int dataTypeId = H5Aget_type(attributeId);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Tclose(dataTypeId);
                }
            });
        return dataTypeId;
    }

    public String getOpaqueTag(int dataTypeId)
    {
        return H5Tget_tag(dataTypeId);
    }

    public int getNativeDataType(int dataTypeId, ICleanUpRegistry registry)
    {
        final int nativeDataTypeId = H5Tget_native_type(dataTypeId);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Tclose(nativeDataTypeId);
                }
            });
        return nativeDataTypeId;
    }

    public int getNativeDataTypeForDataSet(int dataSetId, ICleanUpRegistry registry)
    {
        final int dataTypeId = H5Dget_type(dataSetId);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Tclose(dataTypeId);
                }
            });
        return getNativeDataType(dataTypeId, registry);
    }

    public int getNativeDataTypeForAttribute(int attributeId, ICleanUpRegistry registry)
    {
        final int dataTypeId = H5Aget_type(attributeId);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Tclose(dataTypeId);
                }
            });
        return getNativeDataType(dataTypeId, registry);
    }

    public int getSize(int dataTypeId)
    {
        return H5Tget_size(dataTypeId);
    }

    public boolean isVariableLengthString(int dataTypeId)
    {
        return H5Tis_variable_str(dataTypeId);
    }

    public int getClassType(int dataTypeId)
    {
        return H5Tget_class(dataTypeId);
    }

    public String tryGetDataTypePath(int dataTypeId)
    {
        boolean isCommitted = H5Tcommitted(dataTypeId);
        if (isCommitted)
        {
            final String[] result = new String[1];
            final long len = H5Iget_name(dataTypeId, result, 32);
            if (len >= result[0].length())
            {
                final String[] finalResult = new String[1];
                H5Iget_name(dataTypeId, finalResult, len + 1);
                return finalResult[0];
            } else
            {
                return result[0];
            }
        } else
        {
            return null;
        }
    }

    //
    // Data Space
    //

    public int getDataSpaceForDataSet(int dataSetId, ICleanUpRegistry registry)
    {
        final int dataTypeId = H5Dget_space(dataSetId);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Sclose(dataTypeId);
                }
            });
        return dataTypeId;
    }

    public long[] getDataDimensions(final int dataSetId)
    {
        ICallableWithCleanUp<long[]> dataDimensionRunnable = new ICallableWithCleanUp<long[]>()
            {
                public long[] call(ICleanUpRegistry registry)
                {
                    final int dataSpaceId = H5Dget_space(dataSetId);
                    registry.registerCleanUp(new Runnable()
                        {
                            public void run()
                            {
                                H5Sclose(dataSpaceId);
                            }
                        });
                    final long[] dimensions = getDataSpaceDimensions(dataSpaceId);
                    return dimensions;
                }
            };
        return runner.call(dataDimensionRunnable);
    }

    public long[] getDataMaxDimensions(final int dataSetId)
    {
        ICallableWithCleanUp<long[]> dataDimensionRunnable = new ICallableWithCleanUp<long[]>()
            {
                public long[] call(ICleanUpRegistry registry)
                {
                    final int dataSpaceId = H5Dget_space(dataSetId);
                    registry.registerCleanUp(new Runnable()
                        {
                            public void run()
                            {
                                H5Sclose(dataSpaceId);
                            }
                        });
                    final long[] dimensions = getDataSpaceMaxDimensions(dataSpaceId);
                    return dimensions;
                }
            };
        return runner.call(dataDimensionRunnable);
    }

    public long[] getDataSpaceDimensions(int dataSpaceId)
    {
        final int rank = H5Sget_simple_extent_ndims(dataSpaceId);
        return getDataSpaceDimensions(dataSpaceId, rank);
    }

    public long[] getDataSpaceDimensions(int dataSpaceId, int rank)
    {
        assert dataSpaceId >= 0;
        assert rank >= 0;

        final long[] dimensions = new long[rank];
        H5Sget_simple_extent_dims(dataSpaceId, dimensions, null);
        return dimensions;
    }

    public long[] getDataSpaceMaxDimensions(int dataSpaceId)
    {
        final int rank = H5Sget_simple_extent_ndims(dataSpaceId);
        return getDataSpaceMaxDimensions(dataSpaceId, rank);
    }

    public long[] getDataSpaceMaxDimensions(int dataSpaceId, int rank)
    {
        assert dataSpaceId >= 0;
        assert rank >= 0;

        final long[] maxDimensions = new long[rank];
        H5Sget_simple_extent_dims(dataSpaceId, null, maxDimensions);
        return maxDimensions;
    }

    /**
     * @param dataSetOrAttributeId The id of either the data set or the attribute to get the
     *            dimensions for.
     * @param isAttribute If <code>true</code>, <var>dataSetOrAttributeId</var> will be interpreted
     *            as an attribute, otherwise as a data set.
     * @param dataSetInfo The info object to fill.
     */
    public void fillDataDimensions(final int dataSetOrAttributeId, final boolean isAttribute,
            final HDF5DataSetInformation dataSetInfo)
    {
        ICallableWithCleanUp<Object> dataDimensionRunnable = new ICallableWithCleanUp<Object>()
            {
                public long[][] call(ICleanUpRegistry registry)
                {
                    final int dataSpaceId =
                            isAttribute ? H5Aget_space(dataSetOrAttributeId)
                                    : H5Dget_space(dataSetOrAttributeId);
                    registry.registerCleanUp(new Runnable()
                        {
                            public void run()
                            {
                                H5Sclose(dataSpaceId);
                            }
                        });
                    final long[] dimensions = new long[H5S_MAX_RANK];
                    final long[] maxDimensions = new long[H5S_MAX_RANK];
                    final int rank =
                            H5Sget_simple_extent_dims(dataSpaceId, dimensions, maxDimensions);
                    final long[] realDimensions = new long[rank];
                    System.arraycopy(dimensions, 0, realDimensions, 0, rank);
                    final long[] realMaxDimensions = new long[rank];
                    System.arraycopy(maxDimensions, 0, realMaxDimensions, 0, rank);
                    dataSetInfo.setDimensions(realDimensions);
                    dataSetInfo.setMaxDimensions(realMaxDimensions);
                    return null; // Nothing to return
                }
            };
        runner.call(dataDimensionRunnable);
    }

    public int createSimpleDataSpace(long[] dimensions, ICleanUpRegistry registry)
    {
        final int dataSpaceId = H5Screate_simple(dimensions.length, dimensions, null);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Sclose(dataSpaceId);
                }
            });
        return dataSpaceId;
    }

    public void setHyperslabBlock(int dataSpaceId, long[] start, long[] count)
    {
        assert dataSpaceId >= 0;
        assert start != null;
        assert count != null;

        H5Sselect_hyperslab(dataSpaceId, H5S_SELECT_SET, start, null, count, null);
    }

    //
    // Properties
    //

    public int createLinkCreationPropertyList(boolean createIntermediateGroups,
            ICleanUpRegistry registry)
    {
        final int linkCreationPropertyList = H5Pcreate(H5P_LINK_CREATE);
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Pclose(linkCreationPropertyList);
                }
            });
        if (createIntermediateGroups)
        {
            H5Pset_create_intermediate_group(linkCreationPropertyList, true);
        }
        return linkCreationPropertyList;
    }

    public int createDataSetXferPropertyListAbortOverflow(ICleanUpRegistry registry)
    {
        final int datasetXferPropertyList = H5Pcreate_xfer_abort_overflow();
        registry.registerCleanUp(new Runnable()
            {
                public void run()
                {
                    H5Pclose(datasetXferPropertyList);
                }
            });
        return datasetXferPropertyList;
    }

}