/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.api.index;

import org.eclipse.collections.api.map.primitive.MutableIntObjectMap;
import org.eclipse.collections.impl.factory.primitive.IntObjectMaps;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.neo4j.internal.kernel.api.schema.SchemaDescriptor;
import org.neo4j.internal.kernel.api.schema.SchemaDescriptorSupplier;

import static java.lang.Math.toIntExact;

/**
 * Collects and provides efficient access to {@link SchemaDescriptor}, based on entity token ids and partial or full list of properties.
 * The descriptors are first grouped by entity token id and then for a specific entity token id they are further grouped by property id.
 * The property grouping works on sorted property key id lists as to minimize deduplication when selecting for composite indexes.
 * <p>
 * The selection works efficiently when providing complete list of properties, but will have to resort to some amount of over-selection and union
 * when caller can only provide partial list of properties. The most efficient linking starts from entity token ids and traverses through the property key ids
 * of the schema descriptor (single or multiple for composite index) in sorted order where each property key id (for this entity token) links to the next
 * property key. From all leaves along the way the descriptors can be collected. This way only the actually matching indexes will be collected
 * instead of collecting all indexes matching any property key and doing a union of those. Example:
 *
 * <pre>
 *     Legend: ids inside [] are entity tokens, ids inside () are properties
 *     Descriptors
 *     A: [0](4, 7, 3)
 *     B: [0](7, 4)
 *     C: [0](3, 4)
 *     D: [0](3, 4, 7)
 *     E: [1](7)
 *     F: [1](5, 6)
 *     TODO add multi-entity descriptors
 *
 *     Will result in a data structure (for the optimized path):
 *     [0]
 *        -> (3)
 *           -> (4): C
 *              -> (7): A, D
 *        -> (4)
 *           -> (7): B
 *     [1]
 *        -> (5)
 *           -> (6): F
 *        -> (7): E
 * </pre>
 */
public class SchemaDescriptorLookupSet<T extends SchemaDescriptorSupplier>
{
    private final MutableIntObjectMap<EntityMultiSet> byFirstEntityToken = IntObjectMaps.mutable.empty();
    private final MutableIntObjectMap<PropertyMultiSet> byAnyEntityToken = IntObjectMaps.mutable.empty();

    boolean isEmpty()
    {
        return byFirstEntityToken.isEmpty();
    }

    boolean has( long[] entityTokenIds, int propertyKey )
    {
        if ( byFirstEntityToken.isEmpty() )
        {
            return false;
        }

        for ( int i = 0; i < entityTokenIds.length; i++ )
        {
            EntityMultiSet first = byFirstEntityToken.get( toIntExact( entityTokenIds[i] ) );
            if ( first != null && first.has( entityTokenIds, i, propertyKey ) )
            {
                return true;
            }
        }
        return false;
    }

    boolean has( int entityTokenId )
    {
        return !byAnyEntityToken.isEmpty() && byAnyEntityToken.containsKey( entityTokenId );
    }

    public void add( T schemaDescriptor )
    {
        int[] entityTokenIds = schemaDescriptor.schema().getEntityTokenIds();
        int firstEntityTokenId = entityTokenIds[0];
        byFirstEntityToken.getIfAbsentPut( firstEntityTokenId, EntityMultiSet::new ).add( schemaDescriptor, entityTokenIds, 0 );

        for ( int entityTokenId : entityTokenIds )
        {
            byAnyEntityToken.getIfAbsentPut( entityTokenId, PropertyMultiSet::new ).add( schemaDescriptor );
        }
    }

    public void remove( T schemaDescriptor )
    {
        int[] entityTokenIds = schemaDescriptor.schema().getEntityTokenIds();
        int firstEntityTokenId = entityTokenIds[0];
        EntityMultiSet first = byFirstEntityToken.get( firstEntityTokenId );
        if ( first != null && first.remove( schemaDescriptor, entityTokenIds, 0 ) )
        {
            byFirstEntityToken.remove( firstEntityTokenId );
        }

        for ( int entityTokenId : entityTokenIds )
        {
            PropertyMultiSet any = byAnyEntityToken.get( entityTokenId );
            if ( any != null && any.remove( schemaDescriptor ) )
            {
                byAnyEntityToken.remove( entityTokenId );
            }
        }
    }

    void matchingDescriptorsForCompleteListOfProperties( Collection<T> into, long[] entityTokenIds, int[] sortedProperties )
    {
        for ( int i = 0; i < entityTokenIds.length; i++ )
        {
            int entityTokenId = toIntExact( entityTokenIds[i] );
            EntityMultiSet first = byFirstEntityToken.get( entityTokenId );
            if ( first != null )
            {
                first.collectForCompleteListOfProperties( into, entityTokenIds, i, sortedProperties );
            }
        }
    }

    void matchingDescriptorsForPartialListOfProperties( Collection<T> into, long[] entityTokenIds, int[] sortedProperties )
    {
        for ( int i = 0; i < entityTokenIds.length; i++ )
        {
            int entityTokenId = toIntExact( entityTokenIds[i] );
            EntityMultiSet first = byFirstEntityToken.get( entityTokenId );
            if ( first != null )
            {
                first.collectForPartialListOfProperties( into, entityTokenIds, i, sortedProperties );
            }
        }
    }

    void matchingDescriptors( Collection<T> into, long[] entityTokenIds )
    {
        for ( int i = 0; i < entityTokenIds.length; i++ )
        {
            int entityTokenId = toIntExact( entityTokenIds[i] );
            EntityMultiSet set = byFirstEntityToken.get( entityTokenId );
            if ( set != null )
            {
                set.collectAll( into, entityTokenIds, i );
            }
        }
    }

    private class EntityMultiSet
    {
        private final PropertyMultiSet propertyMultiSet = new PropertyMultiSet();
        private final MutableIntObjectMap<EntityMultiSet> next = IntObjectMaps.mutable.empty();

        void add( T schemaDescriptor, int[] entityTokenIds, int cursor )
        {
            if ( cursor == entityTokenIds.length - 1 )
            {
                propertyMultiSet.add( schemaDescriptor );
            }
            else
            {
                int nextEntityTokenId = entityTokenIds[++cursor];
                next.getIfAbsentPut( nextEntityTokenId, EntityMultiSet::new ).add( schemaDescriptor, entityTokenIds, cursor );
            }
        }

        boolean remove( T schemaDescriptor, int[] entityTokenIds, int cursor )
        {
            if ( cursor == entityTokenIds.length - 1 )
            {
                propertyMultiSet.remove( schemaDescriptor );
            }
            else
            {
                int nextEntityTokenId = entityTokenIds[++cursor];
                EntityMultiSet nextSet = next.get( nextEntityTokenId );
                if ( nextSet != null && nextSet.remove( schemaDescriptor, entityTokenIds, cursor ) )
                {
                    next.remove( nextEntityTokenId );
                }
            }
            return propertyMultiSet.isEmpty() && next.isEmpty();
        }

        void collectForCompleteListOfProperties( Collection<T> into, long[] entityTokenIds, int entityTokenCursor, int[] sortedProperties )
        {
            propertyMultiSet.collectForCompleteListOfProperties( into, sortedProperties );
            // TODO potentially optimize be checking if there even are any next at all before looping? Same thing could be done in PropertyMultiSet
            for ( int i = entityTokenCursor + 1; i < entityTokenIds.length; i++ )
            {
                int nextEntityTokenId = toIntExact( entityTokenIds[i] );
                EntityMultiSet nextSet = next.get( nextEntityTokenId );
                if ( nextSet != null )
                {
                    nextSet.collectForCompleteListOfProperties( into, entityTokenIds, i, sortedProperties );
                }
            }
        }

        void collectForPartialListOfProperties( Collection<T> into, long[] entityTokenIds, int entityTokenCursor, int[] sortedProperties )
        {
            propertyMultiSet.collectForPartialListOfProperties( into, sortedProperties );
            // TODO potentially optimize be checking if there even are any next at all before looping? Same thing could be done in PropertyMultiSet
            for ( int i = entityTokenCursor + 1; i < entityTokenIds.length; i++ )
            {
                int nextEntityTokenId = toIntExact( entityTokenIds[i] );
                EntityMultiSet nextSet = next.get( nextEntityTokenId );
                if ( nextSet != null )
                {
                    nextSet.collectForPartialListOfProperties( into, entityTokenIds, i, sortedProperties );
                }
            }
        }

        void collectAll( Collection<T> into, long[] entityTokenIds, int entityTokenCursor )
        {
            propertyMultiSet.collectAll( into );
            for ( int i = entityTokenCursor + 1; i < entityTokenIds.length; i++ )
            {
                int nextEntityTokenId = toIntExact( entityTokenIds[i] );
                EntityMultiSet nextSet = next.get( nextEntityTokenId );
                if ( nextSet != null )
                {
                    nextSet.collectAll( into, entityTokenIds, i );
                }
            }
        }

        boolean has( long[] entityTokenIds, int entityTokenCursor, int propertyKey )
        {
            if ( propertyMultiSet.has( propertyKey ) )
            {
                return true;
            }
            for ( int i = entityTokenCursor + 1; i < entityTokenIds.length; i++ )
            {
                int nextEntityTokenId = toIntExact( entityTokenIds[i] );
                EntityMultiSet nextSet = next.get( nextEntityTokenId );
                if ( nextSet != null && nextSet.has( entityTokenIds, i, propertyKey ) )
                {
                    return true;
                }
            }
            return false;
        }
    }

    private class PropertyMultiSet
    {
        private final Set<T> descriptors = new HashSet<>();
        private final MutableIntObjectMap<PropertySet> next = IntObjectMaps.mutable.empty();
        private final MutableIntObjectMap<Set<T>> byAnyProperty = IntObjectMaps.mutable.empty();

        void add( T schemaDescriptor )
        {
            // Add optimized path for when property list is fully known
            descriptors.add( schemaDescriptor );
            int[] propertyKeyIds = sortedPropertyKeyIds( schemaDescriptor.schema() );
            int propertyKeyId = propertyKeyIds[0];
            next.getIfAbsentPut( propertyKeyId, PropertySet::new ).add( schemaDescriptor, propertyKeyIds, 0 );

            // Add fall-back path for when property list is only partly known
            for ( int keyId : propertyKeyIds )
            {
                byAnyProperty.getIfAbsentPut( keyId, HashSet::new ).add( schemaDescriptor );
            }
        }

        /**
         * Removes the {@link SchemaDescriptor} from this multi-set.
         * @param schemaDescriptor the {@link SchemaDescriptor} to remove.
         * @return {@code true} if this multi-set ended up empty after removing this descriptor.
         */
        boolean remove( T schemaDescriptor )
        {
            // Remove from the optimized path
            descriptors.remove( schemaDescriptor );
            int[] propertyKeyIds = sortedPropertyKeyIds( schemaDescriptor.schema() );
            int propertyKeyId = propertyKeyIds[0];
            PropertySet firstPropertySet = next.get( propertyKeyId );
            if ( firstPropertySet != null && firstPropertySet.remove( schemaDescriptor, propertyKeyIds, 0 ) )
            {
                next.remove( propertyKeyId );
            }

            // Remove from the fall-back path
            for ( int keyId : propertyKeyIds )
            {
                Set<T> byProperty = byAnyProperty.get( keyId );
                if ( byProperty != null )
                {
                    byProperty.remove( schemaDescriptor );
                    if ( byProperty.isEmpty() )
                    {
                        byAnyProperty.remove( keyId );
                    }
                }
            }
            return descriptors.isEmpty() && next.isEmpty();
        }

        void collectForCompleteListOfProperties( Collection<T> descriptors, int[] sortedProperties )
        {
            for ( int i = 0; i < sortedProperties.length; i++ )
            {
                PropertySet firstSet = next.get( sortedProperties[i] );
                if ( firstSet != null )
                {
                    firstSet.collectForCompleteListOfProperties( descriptors, sortedProperties, i );
                }
            }
        }

        void collectForPartialListOfProperties( Collection<T> descriptors, int[] sortedProperties )
        {
            for ( int propertyKeyId : sortedProperties )
            {
                Set<T> propertyDescriptors = byAnyProperty.get( propertyKeyId );
                if ( propertyDescriptors != null )
                {
                    descriptors.addAll( propertyDescriptors );
                }
            }
        }

        void collectAll( Collection<T> descriptors )
        {
            descriptors.addAll( this.descriptors );
        }

        private int[] sortedPropertyKeyIds( SchemaDescriptor schemaDescriptor )
        {
            int[] propertyKeyIds = schemaDescriptor.getPropertyIds();
            if ( propertyKeyIds.length > 1 )
            {
                propertyKeyIds = propertyKeyIds.clone();
                Arrays.sort( propertyKeyIds );
            }
            return propertyKeyIds;
        }

        boolean has( int propertyKey )
        {
            return byAnyProperty.containsKey( propertyKey );
        }

        boolean isEmpty()
        {
            return descriptors.isEmpty() && next.isEmpty();
        }
    }

    private class PropertySet
    {
        private final Set<T> fullDescriptors = new HashSet<>();
        private final MutableIntObjectMap<PropertySet> next = IntObjectMaps.mutable.empty();

        void add( T schemaDescriptor, int[] propertyKeyIds, int cursor )
        {
            if ( cursor == propertyKeyIds.length - 1 )
            {
                fullDescriptors.add( schemaDescriptor );
            }
            else
            {
                int nextPropertyKeyId = propertyKeyIds[++cursor];
                next.getIfAbsentPut( nextPropertyKeyId, PropertySet::new ).add( schemaDescriptor, propertyKeyIds, cursor );
            }
        }

        /**
         * @param schemaDescriptor {@link SchemaDescriptor} to remove.
         * @param propertyKeyIds the sorted property key ids for this schema.
         * @param cursor which property key among the sorted property keys that this set deals with.
         * @return {@code true} if this {@link PropertySet} ends up empty after this removal.
         */
        boolean remove( T schemaDescriptor, int[] propertyKeyIds, int cursor )
        {
            if ( cursor == propertyKeyIds.length - 1 )
            {
                fullDescriptors.remove( schemaDescriptor );
            }
            else
            {
                int nextPropertyKeyId = propertyKeyIds[++cursor];
                PropertySet propertySet = next.get( nextPropertyKeyId );
                if ( propertySet != null && propertySet.remove( schemaDescriptor, propertyKeyIds, cursor ) )
                {
                    next.remove( nextPropertyKeyId );
                }
            }
            return fullDescriptors.isEmpty() && next.isEmpty();
        }

        void collectForCompleteListOfProperties( Collection<T> descriptors, int[] sortedProperties, int cursor )
        {
            descriptors.addAll( fullDescriptors );
            for ( int i = cursor + 1; i < sortedProperties.length; i++ )
            {
                PropertySet nextSet = next.get( sortedProperties[i] );
                if ( nextSet != null )
                {
                    nextSet.collectForCompleteListOfProperties( descriptors, sortedProperties, i );
                }
            }
        }
    }
}