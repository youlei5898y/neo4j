/*
 * Copyright (c) 2002-2017 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
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
package org.neo4j.kernel.impl.index.schema.combined;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import org.neo4j.kernel.api.exceptions.index.IndexEntryConflictException;
import org.neo4j.kernel.api.index.IndexEntryUpdate;
import org.neo4j.kernel.api.index.IndexPopulator;
import org.neo4j.kernel.api.schema.LabelSchemaDescriptor;
import org.neo4j.values.storable.Value;

import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.core.AnyOf.anyOf;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.neo4j.kernel.impl.index.schema.combined.CombinedIndexTestHelp.add;
import static org.neo4j.kernel.impl.index.schema.combined.CombinedIndexTestHelp.verifyCallFail;

public class CombinedIndexPopulatorTest
{
    private IndexPopulator boostPopulator;
    private IndexPopulator fallbackPopulator;
    private CombinedIndexPopulator combinedIndexPopulator;

    @Before
    public void mockComponents()
    {
        boostPopulator = mock( IndexPopulator.class );
        fallbackPopulator = mock( IndexPopulator.class );
        combinedIndexPopulator = new CombinedIndexPopulator( boostPopulator, fallbackPopulator );
    }

    /* create */

    @Test
    public void createMustCreateBothBoostAndFallback() throws Exception
    {
        // when
        combinedIndexPopulator.create();

        // then
        verify( boostPopulator, times( 1 ) ).create();
        verify( fallbackPopulator, times( 1 ) ).create();
    }

    @Test
    public void createMustThrowIfCreateBoostThrow() throws Exception
    {
        // given
        IOException failure = new IOException( "fail" );
        doThrow( failure ).when( boostPopulator ).create();

        verifyCallFail( failure, () ->
        {
            combinedIndexPopulator.create();
            return null;
        } );
    }

    @Test
    public void createMustThrowIfCreateFallbackThrow() throws Exception
    {
        // given
        IOException failure = new IOException( "fail" );
        doThrow( failure ).when( fallbackPopulator ).create();

        verifyCallFail( failure, () ->
        {
            combinedIndexPopulator.create();
            return null;
        } );
    }

    /* drop */

    @Test
    public void dropMustDropBothBoostAndFallback() throws Exception
    {
        // when
        combinedIndexPopulator.drop();

        // then
        verify( boostPopulator, times( 1 ) ).drop();
        verify( fallbackPopulator, times( 1 ) ).drop();
    }

    @Test
    public void dropMustThrowIfDropBoostThrow() throws Exception
    {
        // given
        IOException failure = new IOException( "fail" );
        doThrow( failure ).when( boostPopulator ).drop();

        verifyCallFail( failure, () ->
        {
            combinedIndexPopulator.drop();
            return null;
        } );
    }

    @Test
    public void dropMustThrowIfDropFallbackThrow() throws Exception
    {
        // given
        IOException failure = new IOException( "fail" );
        doThrow( failure ).when( fallbackPopulator ).drop();

        verifyCallFail( failure, () ->
        {
            combinedIndexPopulator.drop();
            return null;
        } );
    }

    /* add */

    @Test
    public void addMustSelectCorrectPopulator() throws Exception
    {
        // given
        Value[] numberValues = CombinedIndexTestHelp.valuesSupportedByBoost();
        Value[] otherValues = CombinedIndexTestHelp.valuesNotSupportedByBoost();
        Value[] allValues = CombinedIndexTestHelp.allValues();

        // Add with boost for number values
        for ( Value numberValue : numberValues )
        {
            verifyAddWithCorrectPopulator( boostPopulator, fallbackPopulator, numberValue );
        }

        // Add with fallback for other values
        for ( Value otherValue : otherValues )
        {
            verifyAddWithCorrectPopulator( fallbackPopulator, boostPopulator, otherValue );
        }

        // All composite values should go to fallback
        for ( Value firstValue : allValues )
        {
            for ( Value secondValue : allValues )
            {
                verifyAddWithCorrectPopulator( fallbackPopulator, boostPopulator, firstValue, secondValue );
            }
        }
    }

    private void verifyAddWithCorrectPopulator( IndexPopulator correctPopulator, IndexPopulator wrongPopulator, Value... numberValues )
            throws IndexEntryConflictException, IOException
    {
        IndexEntryUpdate<LabelSchemaDescriptor> update = add( numberValues );
        combinedIndexPopulator.add( update );
        verify( correctPopulator, times( 1 ) ).add( update );
        verify( wrongPopulator, times( 0 ) ).add( update );
    }

    /* verifyDeferredConstraints */

    @Test
    public void verifyDeferredConstraintsMustThrowIfBoostThrow() throws Exception
    {
        // given
        IndexEntryConflictException failure = mock( IndexEntryConflictException.class );
        doThrow( failure ).when( boostPopulator ).verifyDeferredConstraints( any() );

        verifyCallFail( failure, () ->
        {
            combinedIndexPopulator.verifyDeferredConstraints( null );
            return null;
        } );
    }

    @Test
    public void verifyDeferredConstraintsMustThrowIfFallbackThrow() throws Exception
    {
        // given
        IndexEntryConflictException failure = mock( IndexEntryConflictException.class );
        doThrow( failure ).when( fallbackPopulator ).verifyDeferredConstraints( any() );

        verifyCallFail( failure, () ->
        {
            combinedIndexPopulator.verifyDeferredConstraints( null );
            return null;
        } );
    }

    /* close */

    @Test
    public void successfulCloseMustCloseBothBoostAndFallback() throws Exception
    {
        // when
        closeAndVerifyPropagation( boostPopulator, fallbackPopulator, combinedIndexPopulator, true );
    }

    @Test
    public void unsuccessfulCloseMustCloseBothBoostAndFallback() throws Exception
    {
        // when
        closeAndVerifyPropagation( boostPopulator, fallbackPopulator, combinedIndexPopulator, false );
    }

    private void closeAndVerifyPropagation( IndexPopulator boostPopulator, IndexPopulator fallbackPopulator,
            CombinedIndexPopulator combinedIndexPopulator, boolean populationCompletedSuccessfully ) throws IOException
    {
        combinedIndexPopulator.close( populationCompletedSuccessfully );

        // then
        verify( boostPopulator, times( 1 ) ).close( populationCompletedSuccessfully );
        verify( fallbackPopulator, times( 1 ) ).close( populationCompletedSuccessfully );
    }

    @Test
    public void closeMustThrowIfCloseBoostThrow() throws Exception
    {
        // given
        IOException failure = new IOException( "fail" );
        doThrow( failure ).when( boostPopulator ).close( anyBoolean() );

        verifyCallFail( failure, () ->
        {
            combinedIndexPopulator.close( anyBoolean() );
            return null;
        } );
    }

    @Test
    public void closeMustThrowIfCloseFallbackThrow() throws Exception
    {
        // given
        IOException failure = new IOException( "fail" );
        doThrow( failure ).when( fallbackPopulator ).close( anyBoolean() );

        verifyCallFail( failure, () ->
        {
            combinedIndexPopulator.close( anyBoolean() );
            return null;
        } );
    }

    @Test
    public void closeMustCloseBoostIfFallbackThrow() throws Exception
    {
        // given
        IOException failure = new IOException( "fail" );
        doThrow( failure ).when( fallbackPopulator ).close( anyBoolean() );

        // when
        try
        {
            combinedIndexPopulator.close( true );
            fail( "Should have failed" );
        }
        catch ( IOException ignore )
        {
        }

        // then
        verify( boostPopulator, times( 1 ) ).close( true );
    }

    @Test
    public void closeMustCloseFallbackIfBoostThrow() throws Exception
    {
        // given
        IOException failure = new IOException( "fail" );
        doThrow( failure ).when( boostPopulator ).close( anyBoolean() );

        // when
        try
        {
            combinedIndexPopulator.close( true );
            fail( "Should have failed" );
        }
        catch ( IOException ignore )
        {
        }

        // then
        verify( fallbackPopulator, times( 1 ) ).close( true );
    }

    @Test
    public void closeMustThrowIfBothThrow() throws Exception
    {
        // given
        IOException boostFailure = new IOException( "boost" );
        IOException fallbackFailure = new IOException( "fallback" );
        doThrow( boostFailure ).when( boostPopulator ).close( anyBoolean() );
        doThrow( fallbackFailure ).when( fallbackPopulator).close( anyBoolean() );

        try
        {
            // when
            combinedIndexPopulator.close( anyBoolean() );
            fail( "Should have failed" );
        }
        catch ( IOException e )
        {
            // then
            assertThat( e, anyOf( sameInstance( boostFailure ), sameInstance( fallbackFailure ) ) );
        }
    }

    /* markAsFailed */

    @Test
    public void markAsFailedMustMarkBothBoostAndFallback() throws Exception
    {
        // when
        String failureMessage = "failure";
        combinedIndexPopulator.markAsFailed( failureMessage );

        // then
        verify( boostPopulator, times( 1 ) ).markAsFailed( failureMessage );
        verify( fallbackPopulator, times( 1 ) ).markAsFailed( failureMessage );
    }

    @Test
    public void markAsFailedMustThrowIfBoostThrow() throws Exception
    {
        // given
        IOException failure = new IOException( "fail" );
        doThrow( failure ).when( boostPopulator ).markAsFailed( anyString() );

        // then
        verifyCallFail( failure, () ->
        {
            combinedIndexPopulator.markAsFailed( anyString() );
            return null;
        } );
    }

    @Test
    public void markAsFailedMustThrowIfFallbackThrow() throws Exception
    {
        // given
        IOException failure = new IOException( "fail" );
        doThrow( failure ).when( fallbackPopulator ).markAsFailed( anyString() );

        // then
        verifyCallFail( failure, () ->
        {
            combinedIndexPopulator.markAsFailed( anyString() );
            return null;
        } );
    }

    // includeSample

    // configureSample
}
