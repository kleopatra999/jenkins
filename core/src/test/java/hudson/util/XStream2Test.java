/*
 * The MIT License
 *
 * Copyright (c) 2004-2010, Sun Microsystems, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.thoughtworks.xstream.XStreamException;
import hudson.XmlFile;
import hudson.matrix.MatrixRun;
import hudson.model.Result;
import hudson.model.Run;
import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.CauseOfInterruption;
import jenkins.model.InterruptedBuildAction;
import junit.framework.TestCase;
import org.apache.commons.io.FileUtils;
import org.jvnet.hudson.test.Bug;

/**
 * Tests for XML serialization of java objects.
 * @author Kohsuke Kawaguchi, Mike Dillon, Alan Harder, Richard Mortimer
 */
public class XStream2Test extends TestCase {

    public static final class Foo {
        Result r1,r2;
    }

    public void testMarshalValue() {
        Foo f = new Foo();
        f.r1 = f.r2 = Result.FAILURE;
        String xml = Run.XSTREAM.toXML(f);
        // we should find two "FAILURE"s as they should be written out twice
        assertEquals(xml, 3, xml.split("FAILURE").length);
    }

    private static class Bar {
        String s;
    }

    /**
     * Test ability to read old XML from Hudson 1.105 or older.
     */
    public void testXStream11Compatibility() {
        Bar b = (Bar)new XStream2().fromXML(
                "<hudson.util.XStream2Test-Bar><s>foo</s></hudson.util.XStream2Test-Bar>");
        assertEquals("foo", b.s);
    }

    public static final class __Foo_Bar$Class {
        String under_1 = "1", under__2 = "2",
               _leadUnder1 = "L1", __leadUnder2 = "L2",
               $dollar = "D1", dollar$2 = "D2";
    }

    /**
     * Test marshal/unmarshal round trip for class/field names with _ and $ characters.
     * (HUDSON-5768)
     */
    public void testXmlRoundTrip() {
        XStream2 xs = new XStream2();
        __Foo_Bar$Class b = new __Foo_Bar$Class();

        String xml = xs.toXML(b);
        __Foo_Bar$Class b2 = (__Foo_Bar$Class)xs.fromXML(xml);

        assertEquals(xml, b.under_1, b2.under_1);
        assertEquals(xml, b.under__2, b2.under__2);
        assertEquals(xml, b._leadUnder1, b2._leadUnder1);
        assertEquals(xml, b.__leadUnder2, b2.__leadUnder2);
        assertEquals(xml, b.$dollar, b2.$dollar);
        assertEquals(xml, b.dollar$2, b2.dollar$2);
    }

    private static class Baz {
        private Exception myFailure;
    }

    /**
     * Verify RobustReflectionConverter can handle missing fields in a class extending
     * Throwable/Exception (default ThrowableConverter registered by XStream calls
     * ReflectionConverter directly, rather than our RobustReflectionConverter replacement).
     * (HUDSON-5769)
     */
    public void testUnmarshalThrowableMissingField() {
        Level oldLevel = disableLogging();
        
        Baz baz = new Baz();
        baz.myFailure = new Exception("foo");

        XStream2 xs = new XStream2();
        String xml = xs.toXML(baz);
        baz = (Baz)xs.fromXML(xml);
        assertEquals("foo", baz.myFailure.getMessage());

        baz = (Baz)xs.fromXML("<hudson.util.XStream2Test_-Baz><myFailure>"
                + "<missingField>true</missingField>"
                + "<detailMessage>hoho</detailMessage>"
                + "<stackTrace><trace>"
                + "hudson.util.XStream2Test.testUnmarshalThrowableMissingField(XStream2Test.java:97)"
                + "</trace></stackTrace>"
                + "</myFailure></hudson.util.XStream2Test_-Baz>");
        // Object should load, despite "missingField" in XML above
        assertEquals("hoho", baz.myFailure.getMessage());
        
        enableLogging(oldLevel);
    }

    private Level disableLogging() {
        Level oldLevel = Logger.getLogger(RobustReflectionConverter.class.getName()).getLevel();
        Logger.getLogger(RobustReflectionConverter.class.getName()).setLevel(Level.OFF);
        return oldLevel;
    }
    
    private void enableLogging(Level oldLevel) {
        Logger.getLogger(RobustReflectionConverter.class.getName()).setLevel(oldLevel);
    }

    private static class ImmutableMapHolder {
        ImmutableMap<?,?> m;
    }

    private static class MapHolder {
        Map<?,?> m;
    }

    public void testImmutableMap() {
        XStream2 xs = new XStream2();

        roundtripImmutableMap(xs, ImmutableMap.of());
        roundtripImmutableMap(xs, ImmutableMap.of("abc", "xyz"));
        roundtripImmutableMap(xs, ImmutableMap.of("abc", "xyz", "def","ghi"));

        roundtripImmutableMapAsPlainMap(xs, ImmutableMap.of());
        roundtripImmutableMapAsPlainMap(xs, ImmutableMap.of("abc", "xyz"));
        roundtripImmutableMapAsPlainMap(xs, ImmutableMap.of("abc", "xyz", "def","ghi"));
    }

    /**
     * Since the field type is {@link ImmutableMap}, XML shouldn't contain a reference to the type name.
     */
    private void roundtripImmutableMap(XStream2 xs, ImmutableMap<?,?> m) {
        ImmutableMapHolder a = new ImmutableMapHolder();
        a.m = m;
        String xml = xs.toXML(a);
        //System.out.println(xml);
        assertFalse("shouldn't contain the class name",xml.contains("google"));
        assertFalse("shouldn't contain the class name",xml.contains("class"));
        a = (ImmutableMapHolder)xs.fromXML(xml);

        assertSame(m.getClass(),a.m.getClass());    // should get back the exact same type, not just a random map
        assertEquals(m,a.m);
    }

    private void roundtripImmutableMapAsPlainMap(XStream2 xs, ImmutableMap<?,?> m) {
        MapHolder a = new MapHolder();
        a.m = m;
        String xml = xs.toXML(a);
        //System.out.println(xml);
        assertTrue("XML should mention the class name",xml.contains('\"'+ImmutableMap.class.getName()+'\"'));
        a = (MapHolder)xs.fromXML(xml);

        assertSame(m.getClass(),a.m.getClass());    // should get back the exact same type, not just a random map
        assertEquals(m,a.m);
    }

    private static class ImmutableListHolder {
        ImmutableList<?> l;
    }

    private static class ListHolder {
        List<?> l;
    }

    public void testImmutableList() {
        XStream2 xs = new XStream2();

        roundtripImmutableList(xs, ImmutableList.of());
        roundtripImmutableList(xs, ImmutableList.of("abc"));
        roundtripImmutableList(xs, ImmutableList.of("abc", "def"));

        roundtripImmutableListAsPlainList(xs, ImmutableList.of());
        roundtripImmutableListAsPlainList(xs, ImmutableList.of("abc"));
        roundtripImmutableListAsPlainList(xs, ImmutableList.of("abc", "def"));
    }

    /**
     * Since the field type is {@link ImmutableList}, XML shouldn't contain a reference to the type name.
     */
    private void roundtripImmutableList(XStream2 xs, ImmutableList<?> l) {
        ImmutableListHolder a = new ImmutableListHolder();
        a.l = l;
        String xml = xs.toXML(a);
        //System.out.println(xml);
        assertFalse("shouldn't contain the class name",xml.contains("google"));
        assertFalse("shouldn't contain the class name",xml.contains("class"));
        a = (ImmutableListHolder)xs.fromXML(xml);

        assertSame(l.getClass(),a.l.getClass());    // should get back the exact same type, not just a random list
        assertEquals(l,a.l);
    }

    private void roundtripImmutableListAsPlainList(XStream2 xs, ImmutableList<?> l) {
        ListHolder a = new ListHolder();
        a.l = l;
        String xml = xs.toXML(a);
        //System.out.println(xml);
        assertTrue("XML should mention the class name",xml.contains('\"'+ImmutableList.class.getName()+'\"'));
        a = (ListHolder)xs.fromXML(xml);

        assertSame(l.getClass(),a.l.getClass());    // should get back the exact same type, not just a random list
        assertEquals(l,a.l);
    }

    @Bug(8006) // Previously a null entry in an array caused NPE
    public void testEmptyStack() {
        assertEquals("<object-array><null/><null/></object-array>",
                     Run.XSTREAM.toXML(new Object[2]).replaceAll("[ \n\r\t]+", ""));
    }

    @Bug(9843)
    public void testCompatibilityAlias() {
        XStream2 xs = new XStream2();
        xs.addCompatibilityAlias("legacy.Point",Point.class);
        Point pt = (Point)xs.fromXML("<legacy.Point><x>1</x><y>2</y></legacy.Point>");
        assertEquals(1,pt.x);
        assertEquals(2,pt.y);
        String xml = xs.toXML(pt);
        //System.out.println(xml);
        assertFalse("Shouldn't use the alias when writing back",xml.contains("legacy"));
    }

    public static class Point {
        public int x,y;
    }

    /**
     * Unmarshall a matrix build.xml result.
     */
    @Bug(10903)
    public void testUnMarshalRunMatrix() {
        InputStream is = XStream2Test.class.getResourceAsStream("runMatrix.xml");
        MatrixRun result = (MatrixRun) Run.XSTREAM.fromXML(is);
        assertNotNull(result);
        assertNotNull(result.getPersistentActions());
        assertEquals(2, result.getPersistentActions().size());
        InterruptedBuildAction action = (InterruptedBuildAction) result.getPersistentActions().get(1);
        assertNotNull(action.getCauses());
        assertEquals(1, action.getCauses().size());
        CauseOfInterruption.UserInterruption cause =
            (CauseOfInterruption.UserInterruption) action.getCauses().get(0);
        assertNotNull(cause);
    }

    public static class Foo2 {
        ConcurrentHashMap<String,String> m = new ConcurrentHashMap<String,String>();
    }

    /**
     * Tests that ConcurrentHashMap is serialized into a more compact format,
     * but still can deserialize to older, verbose format.
     */
    public void testConcurrentHashMapSerialization() throws Exception {
        Foo2 foo = new Foo2();
        foo.m.put("abc","def");
        foo.m.put("ghi","jkl");
        File v = File.createTempFile("hashmap", "xml");
        try {
            new XmlFile(v).write(foo);

            // should serialize like map
            String xml = FileUtils.readFileToString(v);
            assertFalse(xml.contains("java.util.concurrent"));
            //System.out.println(xml);
            Foo2 deserialized = (Foo2) new XStream2().fromXML(xml);
            assertEquals(2,deserialized.m.size());
            assertEquals("def", deserialized.m.get("abc"));
            assertEquals("jkl", deserialized.m.get("ghi"));
        } finally {
            v.delete();
        }

        // should be able to read in old data just fine
        Foo2 map = (Foo2) new XStream2().fromXML(getClass().getResourceAsStream("old-concurrentHashMap.xml"));
        assertEquals(1,map.m.size());
        assertEquals("def",map.m.get("abc"));
    }

    public void testDynamicProxyBlocked() throws Exception { // SECURITY-105
        try {
            ((Runnable) new XStream2().fromXML("<dynamic-proxy><interface>java.lang.Runnable</interface><handler class='java.beans.EventHandler'><target class='" + Hacked.class.getName() + "'/><action>oops</action></handler></dynamic-proxy>")).run();
        } catch (XStreamException x) {
            // good
        }
        assertFalse("should never have run that", Hacked.tripped);
    }
    public static final class Hacked {
        static boolean tripped;
        public void oops() {
            tripped = true;
        }
    }

    public void testTrimVersion() throws Exception {
        assertEquals("3.2", XStream2.trimVersion("3.2"));
        assertEquals("3.2.1", XStream2.trimVersion("3.2.1"));
        assertEquals("3.2-SNAPSHOT", XStream2.trimVersion("3.2-SNAPSHOT (private-09/23/2012 12:26-jhacker)"));
    }

}
