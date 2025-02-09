/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.cassandra;

import com.datastax.oss.driver.internal.core.metadata.token.Murmur3Token;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static java.math.BigInteger.ONE;
import static java.math.BigInteger.ZERO;
import static org.testng.Assert.assertEquals;

public class TestMurmur3PartitionerTokenRing
{
    private static final Murmur3PartitionerTokenRing tokenRing = Murmur3PartitionerTokenRing.INSTANCE;

    @Test
    public void testGetTokenCountInRange()
    {
        assertEquals(tokenRing.getTokenCountInRange(new Murmur3Token(0), new Murmur3Token(1)), ONE);
        assertEquals(tokenRing.getTokenCountInRange(new Murmur3Token(-1), new Murmur3Token(1)), new BigInteger("2"));
        assertEquals(tokenRing.getTokenCountInRange(new Murmur3Token(-100), new Murmur3Token(100)), new BigInteger("200"));
        assertEquals(tokenRing.getTokenCountInRange(new Murmur3Token(0), new Murmur3Token(10)), new BigInteger("10"));
        assertEquals(tokenRing.getTokenCountInRange(new Murmur3Token(1), new Murmur3Token(11)), new BigInteger("10"));
        assertEquals(tokenRing.getTokenCountInRange(new Murmur3Token(0), new Murmur3Token(0)), ZERO);
        assertEquals(tokenRing.getTokenCountInRange(new Murmur3Token(1), new Murmur3Token(1)), ZERO);
        assertEquals(tokenRing.getTokenCountInRange(new Murmur3Token(Long.MIN_VALUE), new Murmur3Token(Long.MIN_VALUE)), BigInteger.valueOf(2).pow(64).subtract(ONE));
        assertEquals(tokenRing.getTokenCountInRange(new Murmur3Token(1), new Murmur3Token(0)), BigInteger.valueOf(2).pow(64).subtract(BigInteger.valueOf(2)));
    }

    @Test
    public void testGetRingFraction()
    {
        assertEquals(tokenRing.getRingFraction(new Murmur3Token(1), new Murmur3Token(1)), 0.0, 0.001);
        assertEquals(tokenRing.getRingFraction(new Murmur3Token(1), new Murmur3Token(0)), 1.0, 0.001);
        assertEquals(tokenRing.getRingFraction(new Murmur3Token(0), new Murmur3Token(Long.MAX_VALUE)), 0.5, 0.001);
        assertEquals(tokenRing.getRingFraction(new Murmur3Token(Long.MIN_VALUE), new Murmur3Token(Long.MAX_VALUE)), 1.0, 0.001);
        assertEquals(tokenRing.getRingFraction(new Murmur3Token(Long.MIN_VALUE), new Murmur3Token(Long.MIN_VALUE)), 1.0, 0.001);
    }
}
