/**
 ************************ 80 columns *******************************************
 * TestLatexDiff
 *
 * Created on 12/20/11.
 *
 * Copyright 2009-2010, SRI International.
 */
package com.sri.ltc.latexdiff;

import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

/**
 * @author linda
 */
public class TestLatexDiff {

    private static final LatexDiff latexDiff = new LatexDiff();
    protected List<Change> changes;

    protected static List<Change> getChanges(String text1, String text2) throws IOException {
        return latexDiff.getChanges(
                new StringReaderWrapper(text1),
                new StringReaderWrapper(text2));
    }

    protected void assertAddition(int index, int start_position, int end_position, Set<Change.Flag> flags) {
        assertTrue("at least "+(index+1)+" changes", changes.size() >= index+1);
        Change change = changes.get(index);
        assertTrue("change is addition", change instanceof Addition);
        assertTrue("start is at "+start_position, change.start_position == start_position);
        assertTrue("end is at "+end_position, ((Addition) change).end_position == end_position);
        assertEquals("flags are "+flags, flags, change.flags);
    }

    protected void assertDeletion(int index, int start_position, int length, Set<Change.Flag> flags) {
        assertTrue("at least "+(index+1)+" changes", changes.size() >= index+1);
        Change change = changes.get(index);
        assertTrue("change is deletion", change instanceof Deletion);
        assertTrue("start is at "+start_position, change.start_position == start_position);
        assertTrue("length is "+length, ((Deletion) change).text.length() == length);
        assertEquals("flags are "+flags, flags, change.flags);
    }

    @Test(expected = NullPointerException.class)
    public void nullReader() throws IOException {
        changes = getChanges("", null);
    }

    @Test
    public void whitespace() throws IOException {
        changes = getChanges("", " \n   \t");
        assertTrue("Changes is empty", changes.isEmpty());
        changes = getChanges(
                "   Lorem ipsum \n dolor sit amet. ",
                "Lorem ipsum dolor sit amet.");
        assertTrue("Changes is empty", changes.isEmpty());
        changes = getChanges("   \n ", " \t  ");
        assertTrue("Changes is empty", changes.isEmpty());
        changes = getChanges(
                "   Lorem ipsum \n \ndolor sit amet. ",
                "Lorem ipsum dolor sit amet.");
        assertDeletion(0, 11, 4, EnumSet.of(Change.Flag.DELETION));
        changes = getChanges(
                "Lorem ipsum dolor sit amet.",
                "   Lorem ipsum \n \ndolor sit amet. ");
        assertAddition(0, 14, 18, EnumSet.noneOf(Change.Flag.class));
    }

    @Ignore
    public void inComment() throws IOException {
        changes = getChanges(
                " \nLorem ipsum %%%  HERE IS A COMMMENT WITH SPACE...\n dolor sit amet. \n ",
                "Lorem ipsum \n%%%  HERE IS A COMMENT WITH SPACE AND MORE %...\n dolor sit amet."
        );
        assertTrue("2 changes", changes.size() == 2);
        Change change = changes.get(0);
        assertTrue("1st change is small deletion", change instanceof SmallDeletion);
        assertTrue("1st change is in comment but not in preamble nor a command",
                !change.flags.contains(Change.Flag.PREAMBLE)
                        && !change.flags.contains(Change.Flag.COMMAND)
                        && change.flags.contains(Change.Flag.COMMENT));
        change = changes.get(1);
        assertTrue("2nd change is addition", change instanceof Addition);
        assertTrue("2nd change has 4 lexemes", ((Addition) change).lexemes.size() == 4);
        assertTrue("2nd change is in comment but not in preamble nor a command",
                !change.flags.contains(Change.Flag.PREAMBLE)
                        && !change.flags.contains(Change.Flag.COMMAND)
                        && change.flags.contains(Change.Flag.COMMENT));
    }

    @Test
    public void inPreamble() throws IOException {
        changes = getChanges(
                " \n\n \\begin{document}  \n \nLorem ipsum \n dolor sit amet. \n ",
                " \n\\usepackage{lipsum}\n \\begin{document}  \n \nLorem ipsum \n dolor sit amet. \n "
        );
        assertAddition(0, 0, 13, EnumSet.of(Change.Flag.COMMAND, Change.Flag.PREAMBLE));
        assertAddition(1, 13, 23, EnumSet.of(Change.Flag.PREAMBLE));
        changes = getChanges(
                " \n\\usepackage{lipsum}\n \\begin{document}  \n \nLorem ipsum \n dolor sit amet. \n ",
                " \n\n \\begin{document}  \n \nLorem ipsum \n dolor sit amet. \n "
        );
        assertDeletion(0, 0, 13, EnumSet.of(Change.Flag.DELETION, Change.Flag.COMMAND, Change.Flag.PREAMBLE));
        assertDeletion(1, 13, 8, EnumSet.of(Change.Flag.DELETION, Change.Flag.PREAMBLE));
        changes = getChanges(
                " \n\\usepackage{lipsum}\n \\begin{document}  \n \nLorem ipsum \n dolor sit amet. \n ",
                " \n\n % start doc\n\n\\begin{document}  \n \nLorem ipsum \n dolor sit amet. \n "
        );
        assertAddition(0, 0, 17, EnumSet.of(Change.Flag.PREAMBLE, Change.Flag.COMMENT));
        assertDeletion(1, 0, 13, EnumSet.of(Change.Flag.DELETION, Change.Flag.COMMAND, Change.Flag.PREAMBLE));
        assertDeletion(2, 13, 8, EnumSet.of(Change.Flag.DELETION, Change.Flag.PREAMBLE));
    }
}
