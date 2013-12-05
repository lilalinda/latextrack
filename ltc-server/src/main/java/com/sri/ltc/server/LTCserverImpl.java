/*
 * #%L
 * LaTeX Track Changes (LTC) allows collaborators on a version-controlled LaTeX writing project to view and query changes in the .tex documents.
 * %%
 * Copyright (C) 2009 - 2012 SRI International
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the 
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public 
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */
package com.sri.ltc.server;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.sri.ltc.CommonUtils;
import com.sri.ltc.ProgressReceiver;
import com.sri.ltc.filter.Author;
import com.sri.ltc.filter.Filtering;
import com.sri.ltc.versioncontrol.history.CompleteHistory;
import com.sri.ltc.versioncontrol.history.HistoryUnit;
import com.sri.ltc.versioncontrol.history.LimitedHistory;
import com.sri.ltc.latexdiff.*;
import com.sri.ltc.versioncontrol.*;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.xmlrpc.XmlRpcException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.swing.text.BadLocationException;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.awt.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.*;
import java.text.ParseException;
import java.util.*;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * @author linda
 */
public final class LTCserverImpl implements LTCserverInterface {

    private final Preferences preferences = Preferences.userNodeForPackage(this.getClass());
    private final static List<Color> defaultColors = new ArrayList<Color>(Arrays.asList(
            Color.blue, Color.red,
            Color.decode("#009900"), // darker green
            Color.decode("#990066"), // darker magenta
            Color.decode("#FF6600"), // darker orange
            Color.decode("#00CCFF"))); // darker cyan/light blue
    public final static int NUM_DEFAULT_COLORS = defaultColors.size();
    private final static String KEY_COLOR = "author-color:";
    private final ProgressReceiver progressReceiver;

    public LTCserverImpl() {
        this.progressReceiver = null;
    }

    public LTCserverImpl(ProgressReceiver progressReceiver) {
        this.progressReceiver = progressReceiver;
    }

    private void updateProgress(int progress) {
        if (progressReceiver != null)
            progressReceiver.updateProgress(progress);
    }

    private final static Logger LOGGER = Logger.getLogger(LTCserverImpl.class.getName());

    private void logAndThrow(int code, Throwable cause) throws XmlRpcException {
        XmlRpcException e = new XmlRpcException(code, cause.getMessage(), cause);
        LOGGER.log(Level.SEVERE, e.getMessage(), e);
        throw e;
    }

    private Session getSession(int sessionID) throws XmlRpcException {
        Session session = SessionManager.getSession(sessionID);
        if (session == null)
            logAndThrow(1, new RuntimeException("Cannot retrieve session with given ID "+sessionID));
        return session;
    }

    // -- begin API implementation
    // TODO: apply locks to make block atomic...?

    public int hello() {
        LOGGER.info("Server: Hello World!");
        return 42;
    }

    public int init_session(String path) throws XmlRpcException {
        if (path == null)
            logAndThrow(1, new IllegalArgumentException("Given path is NULL"));

        LOGGER.info("Server: init_session with file \""+path+"\" called.");

        // is given path a valid file?
        File file = new File(path);
        if (!file.canRead())
            logAndThrow(2, new RuntimeException("Cannot read file "+file.getAbsolutePath()));

        Repository repository = null;
        try {
            repository = RepositoryFactory.fromPath(file);
        } catch (Exception e) {
            logAndThrow(3, new RuntimeException("Could not find version control (git or svn) for file "+file.getAbsolutePath()))   ;
        }
        updateProgress(3);

        try {
            TrackedFile trackedFile = repository.getFile(file);
            // test whether file tracked under git
            switch (trackedFile.getStatus()) {
                case NotTracked:
                    logAndThrow(4, new RuntimeException("File \""+trackedFile.getFile().getName()+"\" not tracked under version control"));
                    break;
                case Removed:
                    logAndThrow(5, new RuntimeException("File \""+trackedFile.getFile().getName()+"\" deleted or deleted to commit under version control"));
                    break;
                case Unknown:
                    logAndThrow(6, new RuntimeException("File \""+trackedFile.getFile().getName()+"\" status unknown under version control"));
                    break;

                default:
                    break;
            }
            updateProgress(10);

            return SessionManager.createSession(trackedFile);
        } catch (IOException e) {
            logAndThrow(7, e);
        } catch (ParseException e) {
            logAndThrow(8, e);
        } catch (VersionControlException e) {
            logAndThrow(10, e);
        } catch (IllegalStateException e) {
            logAndThrow(11, new RuntimeException("Tracked file already active under another session", e));
        }

        return -1;
    }

    @SuppressWarnings("unchecked")
    public Map close_session(int sessionID, byte[] currentText64, List deletions, int caretPosition) throws XmlRpcException {
        Session session = SessionManager.finishSession(sessionID);
        if (session == null)
            logAndThrow(1, new RuntimeException("Cannot close session with given ID "+sessionID));

        // translate current text
        String currentText = new String(Base64.decodeBase64(currentText64));

        LOGGER.info("Server: close_session for file \""+session.getTrackedFile().getFile().getAbsolutePath()+"\", "+
                "text with "+currentText.length()+" characters, "+
                (deletions != null?
                        deletions.size()+" deletions, ":
                        "")+
                "and caret at "+caretPosition+" called.");

        // apply deletions to current text and update caret position
        try {
            if (deletions != null && !deletions.isEmpty()) {
                MarkedUpDocument document = new MarkedUpDocument(currentText, deletions, caretPosition);
                document.removeDeletions();
                currentText = document.getText(0, document.getLength());
                caretPosition = document.getCaretPosition();
            }
        } catch (BadLocationException e) {
            logAndThrow(2, e);
        }

        // create return value
        Map map = new HashMap();
        map.put(LTCserverInterface.KEY_TEXT, Base64.encodeBase64(currentText.getBytes()));
        map.put(LTCserverInterface.KEY_CARET, caretPosition);
        return map;
    }

    @SuppressWarnings("unchecked")
    public int save_file(int sessionID, byte[] currentText64, List deletions) throws XmlRpcException {
        Session session = getSession(sessionID);

        // translate current text
        String currentText = new String(Base64.decodeBase64(currentText64));

        LOGGER.info("Server: save_file to file \""+session.getTrackedFile().getFile().getAbsolutePath()+"\" "+
                "text with "+ currentText.length()+" characters"+
                (deletions != null?
                        " and "+deletions.size()+" deletions":
                        "")+
                " called.");

        try {
            if (deletions != null && !deletions.isEmpty()) {
                MarkedUpDocument document = new MarkedUpDocument(currentText, deletions, 0);
                document.removeDeletions();
                currentText = document.getText(0, document.getLength());
            }
            // write current text to file
            BufferedWriter out = new BufferedWriter(new FileWriter(session.getTrackedFile().getFile(), false));
            out.write(currentText);
            out.close();
        } catch (BadLocationException e) {
            logAndThrow(2, e);
        } catch (FileNotFoundException e) {
            logAndThrow(4, e);
        } catch (IOException e) {
            logAndThrow(3, e);
        }

        return 0;
    }

    @SuppressWarnings (value={"unchecked","fallthrough"})
    public Map get_changes(int sessionID, boolean isModified, byte[] currentText64, List deletions, int caretPosition) throws XmlRpcException {
        Session session = getSession(sessionID);

        // translate current text
        String currentText = new String(Base64.decodeBase64(currentText64));

        LOGGER.info("Server: get_changes for file \""+session.getTrackedFile().getFile().getAbsolutePath()+"\", "+
                (isModified?"":"not ")+"modified, "+
                "text with "+ currentText.length()+" characters, "+
                (deletions != null?
                        deletions.size()+" deletions, ":
                        "")+
                "and caret at "+caretPosition+" called.");

        // apply deletions to current text and update caret position
        try {
            Map<MarkedUpDocument.KEYS,Object> map =
                    MarkedUpDocument.applyDeletions(currentText, deletions, caretPosition);
            currentText = (String) map.get(MarkedUpDocument.KEYS.TEXT);
            caretPosition = (Integer) map.get(MarkedUpDocument.KEYS.POSITION);
        } catch (BadLocationException e) {
            logAndThrow(7, e);
        }
        updateProgress(10);

        Filtering filter = Filtering.getInstance();
        Author self = session.getTrackedFile().getRepository().getSelf();

        // obtain file history from version control, disk, and session (obeying any filters):
        List<HistoryUnit> units = new ArrayList<HistoryUnit>();
        try {
            // create history with limits and obtain revision IDs, authors, and readers:
            LimitedHistory history = session.createLimitedHistory(filter.getStatus(BoolPrefs.COLLAPSE_AUTHORS)); // whether to condense authors or not
            updateProgress(12);
            units = history.getHistoryUnits();
            updateProgress(45);

            // add file on disk to list, if modified or new but not committed yet
            switch (session.getTrackedFile().getStatus()) {
                case Added:
                case Modified:
                case Changed:
                case Conflicting: // TODO: once we implement merge assistance, maybe this gets handled differently
                    HistoryUnit last = Iterables.getLast(units, null);
                    if (last != null && filter.getStatus(BoolPrefs.COLLAPSE_AUTHORS) && last.author.equals(self))
                        // replace last unit if collapsing authors:
                        units.remove(units.size()-1);
                    // add new unit for ON DISK:
                    units.add(new HistoryUnit(self,
                            LTCserverInterface.ON_DISK,
                            new FileReaderWrapper(session.getTrackedFile().getFile().getCanonicalPath())));
            }
            // add current text from editor, if modified since last save:
            if (isModified) {
                // check if ON DISK or collapsing authors && last one equals self:
                HistoryUnit last = Iterables.getLast(units, null);
                if (last != null &&
                        (LTCserverInterface.ON_DISK.equals(last.revision) ||
                                (filter.getStatus(BoolPrefs.COLLAPSE_AUTHORS) && last.author.equals(self))))
                    // replace last unit
                    units.remove(units.size()-1);
                // add new unit for MODIFIED:
                units.add(new HistoryUnit(self,
                        LTCserverInterface.MODIFIED,
                        new StringReaderWrapper(currentText)));
            }
            // if no history, then use text from file and self as author
            if (units.isEmpty())
                units.add(new HistoryUnit(self, "",
                        new FileReaderWrapper(session.getTrackedFile().getFile().getCanonicalPath())));
            updateProgress(47);
        } catch (IOException e) {
            logAndThrow(5, e);
        } catch (ParseException e) {
            logAndThrow(6, e);
        } catch (Exception e) {
            logAndThrow(7, e);
        }

        // compute colors and indices of authors:
        List<Color> defaultColors = Lists.newArrayList(LTCserverImpl.defaultColors); // copy default colors
        Set<Color> currentColors = Sets.newHashSet(); // keep track of current colors if we need to be unique
        List<Author> authors = Lists.transform(units, new Function<HistoryUnit, Author>() {
            @Nullable
            @Override
            public Author apply(@Nullable HistoryUnit unit) {
                return unit.author;
            }
        });
        session.addAuthors(new HashSet<Author>(authors));
        Map<Integer,Object[]> mappedAuthors = new HashMap<Integer,Object[]>();
        List<Integer> indices = new ArrayList<Integer>();
        if (authors != null && authors.size() > 0) {
            List<Author> sortedAuthors = new ArrayList<Author>(new TreeSet<Author>(authors));
            // assign color (if unique colors requested) and then
            // build up map: index -> author and color (as list)
            for (Author a : sortedAuthors) {
                if (!filter.getStatus(BoolPrefs.ALLOW_SIMILAR_COLORS))  // create unique colors for all authors + self
                    try {
                        computeUniqueColor(a, defaultColors, currentColors);
                    } catch (BackingStoreException e) {
                        logAndThrow(8, e);
                    }
                mappedAuthors.put(sortedAuthors.indexOf(a), concatAuthorAndColor(a));
            }
            // build up list of indices
            for (Author a : authors)
                indices.add(sortedAuthors.indexOf(a));
        }
        // handle self if not already computed:
        if (!authors.contains(self) && !filter.getStatus(BoolPrefs.ALLOW_SIMILAR_COLORS))  // create unique colors for all authors + self
            try {
                computeUniqueColor(self, defaultColors, currentColors);
                mappedAuthors.put(-1, concatAuthorAndColor(self));
            } catch (BackingStoreException e) {
                logAndThrow(8, e);
            }
        updateProgress(50);

        // do diffs and accumulate changes:
        Map map = null;
        try {
            PropertyChangeListener listener = null;
            if (progressReceiver != null) {
                listener = new PropertyChangeListener() {
                    @Override
                    public void propertyChange(PropertyChangeEvent e) {
                        if (Accumulate.PROGRESS_PROPERTY.equals(e.getPropertyName())) {
                            updateProgress(50+(int) (((Float) e.getNewValue())*40f));
                        }
                    }
                };
                session.getAccumulate().addPropertyChangeListener(listener);
            }
            List<ReaderWrapper> readers = Lists.transform(units, new Function<HistoryUnit, ReaderWrapper>() {
                @Nullable
                @Override
                public ReaderWrapper apply(@Nullable HistoryUnit unit) {
                    return unit.reader;
                }
            });
            map = session.getAccumulate().perform(
                    readers.toArray(new ReaderWrapper[readers.size()]),
                    indices.toArray(new Integer[indices.size()]),
                    Change.buildFlagsToHide(
                            filter.getStatus(BoolPrefs.DELETIONS),
                            filter.getStatus(BoolPrefs.SMALL),
                            filter.getStatus(BoolPrefs.PREAMBLE),
                            filter.getStatus(BoolPrefs.COMMENTS),
                            filter.getStatus(BoolPrefs.COMMANDS)),
                    caretPosition);
            map.put(LTCserverInterface.KEY_AUTHORS, mappedAuthors); // add current author map
            map.put(LTCserverInterface.KEY_REVS, Lists.transform(units.subList(1, units.size()),
                    new Function<HistoryUnit, String>() {
                        @Nullable
                        @Override
                        public String apply(@Nullable HistoryUnit unit) {
                            return unit.revision;
                        }
                    })); // add list of revisions used in accumulation: remove the base version!
            map.put(LTCserverInterface.KEY_LAST, units.get(0).revision); // base version
            session.getAccumulate().removePropertyChangeListener(listener);
        } catch (Exception e) {
            logAndThrow(2, e);
        }
        updateProgress(90);

        return map;
    }

    private void computeUniqueColor(Author a, List<Color> defaultColors, Set<Color> currentColors) throws BackingStoreException, XmlRpcException {
        // obtain any stored color from preferences
        Color storedColor = null;
        synchronized (preferences) {
            String authorKey = getColorKey(a);
            if (Sets.newHashSet(preferences.keys()).contains(authorKey))
                storedColor = new Color(preferences.getInt(authorKey, 0));
        }
        if (storedColor == null)
            storedColor = getDefaultOrRandom(defaultColors);
        // test that the current one is unique enough:
        int i = 0;
        for (; i < CommonUtils.MAX_TRIES_COLOR && isSimilarTo(storedColor, currentColors); i++) {
            storedColor = getDefaultOrRandom(defaultColors);
            LOGGER.info("Replacing color for author "+a+" with color "+storedColor);
        }
        if (i == CommonUtils.MAX_TRIES_COLOR)
            LOGGER.warning("Couldn't replace color for author "+a+" with a random one that is unique.");
        set_color(a.name, a.email, convertToHex(storedColor));
        currentColors.add(storedColor);
    }

    private Color getDefaultOrRandom(@Nonnull List<Color> defaultColors) {
        Color color = Color.getHSBColor((float) Math.random(), 0.85f, 1.0f);
        if (!defaultColors.isEmpty()) {
            color = defaultColors.get(0);
            defaultColors.remove(0);
        }
        return color;
    }

    // test whether given color has a similar one in given set
    private boolean isSimilarTo(@Nonnull Color color, @Nonnull Set<Color> colors) {
        for (Color other : colors)
            if (CommonUtils.isSimilarTo(color, other))
                return true;
        return false;
    }

    public String get_VCS(int sessionID) throws XmlRpcException {
        Session session = getSession(sessionID);

        return session.getTrackedFile().getRepository().getVCS().name();
    }

    private Object[] concatAuthorAndColor(Author author) throws XmlRpcException {
        Object[] authorAndColor = new Object[3];
        System.arraycopy(author.asList(), 0, authorAndColor, 0, 2);
        authorAndColor[2] = get_color(author.name, author.email);
        return authorAndColor;
    }

    public List get_authors(int sessionID) throws XmlRpcException {
        Session session = getSession(sessionID);
        List<Object[]> result = new ArrayList<Object[]>();
        for (Author author : session.getAuthors())
            result.add(concatAuthorAndColor(author));
        return result;
    }

    public List get_commits(int sessionID) throws XmlRpcException {
        Session session = getSession(sessionID);
        try {
            return new CompleteHistory(session.getTrackedFile()).update();
        } catch (ParseException e) {
            logAndThrow(2, e);
        } catch (IOException e) {
            logAndThrow(4, e);
        } catch (Exception e) {
            logAndThrow(5, e);
        }
        return null;
    }

    public Object[] get_self(int sessionID) throws XmlRpcException {
        Session session = getSession(sessionID);
        try {
            return concatAuthorAndColor(session.getTrackedFile().getRepository().getSelf());
        } catch (IllegalArgumentException e) {
            // ignore
        } catch (Exception e) {
            logAndThrow(2, e);
        }
        return new Object[0]; // if self is undefined
    }

    public Object[] set_self(int sessionID, String name, String email) throws XmlRpcException {
        Session session = getSession(sessionID);
        try {
            Author a = new Author(name, email);
            session.getTrackedFile().getRepository().setSelf(a);
            session.addAuthors(Collections.singleton(a));
        } catch (IllegalArgumentException e) {
            // thrown by constructor of Author
            logAndThrow(4, e);
        }
        return get_self(sessionID);
    }

    public Object[] reset_self(int sessionID) throws XmlRpcException {
        Session session = getSession(sessionID);
        try {
            session.getTrackedFile().getRepository().resetSelf();
        } catch (Exception e) {
            logAndThrow(5, e);
        }
        return get_self(sessionID);
    }

    public List get_limited_authors(int sessionID) throws XmlRpcException {
        Session session = getSession(sessionID);
        // build up list as intersection of currently limited authors and known ones
        List<Object[]> result = new ArrayList<Object[]>();
        Set<Author> limitedAuthors = session.getLimitedAuthors();
        if (limitedAuthors.isEmpty())
            return result;
        for (Author author : session.getAuthors())
            if (limitedAuthors.contains(author))
                result.add(concatAuthorAndColor(author));
        return result;
    }

    @SuppressWarnings("unchecked")
    public List set_limited_authors(int sessionID, List authors) throws XmlRpcException {
        Session session = getSession(sessionID);
        session.resetLimitedAuthors();
        try {
            for (Object[] authorAsList : (List<Object[]>) authors)
                session.addLimitedAuthor(Author.fromList(authorAsList));
        } catch (Exception e) {
            logAndThrow(2, e);
        }
        return get_limited_authors(sessionID);
    }

    public int reset_limited_authors(int sessionID) throws XmlRpcException {
        Session session = getSession(sessionID);
        session.resetLimitedAuthors();
        return 0;
    }

    public String set_limited_date(int sessionID, String date) throws XmlRpcException {
        Session session = getSession(sessionID);
        session.setLimitDate(date);
        return session.getLimitDate();
    }

    public String get_limited_date(int sessionID) throws XmlRpcException {
        Session session = getSession(sessionID);
        return session.getLimitDate();
    }

    public int reset_limited_date(int sessionID) throws XmlRpcException {
        set_limited_date(sessionID, null);
        return 0;
    }

    public String set_limited_rev(int sessionID, String rev) throws XmlRpcException {
        Session session = getSession(sessionID);
        LOGGER.fine("Setting limiting rev: "+rev);
        session.setLimitRev(rev);
        return session.getLimitRev();
    }

    public String get_limited_rev(int sessionID) throws XmlRpcException {
        Session session = getSession(sessionID);
        return session.getLimitRev();
    }

    public int reset_limited_rev(int sessionID) throws XmlRpcException {
        set_limited_rev(sessionID, null);
        return 0;
    }

    public boolean get_bool_pref(String key) throws XmlRpcException {
        try {
            BoolPrefs boolPref = BoolPrefs.valueOf(key);
            return Filtering.getInstance().getStatus(boolPref);
        } catch (IllegalArgumentException e) {
            logAndThrow(1, e);
        }
        return false;
    }

    public int set_bool_pref(String key, boolean value) throws XmlRpcException {
        try {
            BoolPrefs boolPref = BoolPrefs.valueOf(key);
            Filtering.getInstance().setStatus(boolPref, value);
            LOGGER.fine("Server: turning boolean preference for \"" + key + (value ? "\" on." : "\" off."));
        } catch (IllegalArgumentException e) {
            logAndThrow(1, e);
        }
        return 0;
    }

    public int reset_bool_prefs() {
        Filtering.getInstance().resetAllStatus();
        LOGGER.info("Server: resetting all boolean preferences to default.");
        return 0;
    }

    public Object[] get_bool_pref_items() {
        List<String> names = new ArrayList<String>();
        for (BoolPrefs boolPref : BoolPrefs.values())
            names.add(boolPref.name());
        return names.toArray();
    }

    @Override
    public String create_bug_report(int sessionID, String message, boolean includeRepository, String outputDirectory) throws XmlRpcException {
        LOGGER.fine("Server: creating bug report in directory \""+outputDirectory+"\"");

        if (outputDirectory == null || outputDirectory.isEmpty())
            logAndThrow(5, new IllegalArgumentException("Cannot create bug report with empty or NULL output directory"));

        File outputDirectoryFile = new File(outputDirectory);
        if (outputDirectoryFile.mkdirs()) {
            LOGGER.fine("Created report directory " + outputDirectory);
        }

        // bundle repository
        File bundle = null;
        if (includeRepository)
            try {
                bundle = getSession(sessionID).getTrackedFile().getRepository().getBundle(new File(outputDirectory));
            } catch (IOException e) {
                logAndThrow(4, e);
            }

        // create an XML with current settings etc.
        File xmlFile = new File(outputDirectory, "report.xml");
        create_bug_report_xml(sessionID, message, bundle, xmlFile.getAbsolutePath());

        // create zip with additional files
        File zipFile = new File(outputDirectory, "report.zip");
        LOGGER.fine("Server: zipping up bug report as \""+zipFile.getAbsolutePath()+"\"");
        try {
            ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile, false));

            // add "report.xml" and remove it
            copyToZip(zos, xmlFile, xmlFile.getName());
            if (!xmlFile.delete())
                LOGGER.warning("Couldn't delete file "+xmlFile.getAbsolutePath());
            xmlFile = null;

            // add log file(s), if they exist
            File[] logFiles = new File(System.getProperty("user.home")).listFiles(CommonUtils.LOG_FILE_FILTER);
            if (logFiles != null)
                for (File logFile : logFiles) {
                    LOGGER.fine("Adding log file " + logFile.getName() + " to bug report");
                    copyToZip(zos, logFile, logFile.getName().substring(1)); // remove leading "." from name
                }

            // add bundle file (if exists)
            if (bundle != null) {
                LOGGER.fine("Adding bundle " + bundle.getName() + " to bug report");
                copyToZip(zos, bundle, bundle.getName());
            }

            // add Messages.txt (*+if exists)
            File msgFile = new File(outputDirectory, "Messages.txt");
            if (msgFile.exists()) {
                LOGGER.fine("Adding Emacs *Messages* " + msgFile.getName() + " to bug report");
                copyToZip(zos, msgFile, msgFile.getName());
            }

            zos.close();
        } catch (FileNotFoundException e) {
            logAndThrow(2, e);
        } catch (IOException e) {
            logAndThrow(3, e);
        }

        return zipFile.getAbsolutePath();
    }

    private void copyToZip(ZipOutputStream zos, File file, String name) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        FileInputStream fis = new FileInputStream(file);
        byte[] buf = new byte[1024];
        int len;
        while ((len = fis.read(buf)) >= 0)
            zos.write(buf, 0, len);
        zos.closeEntry();
    }

    private String getColorKey(Author author) {
        String key = KEY_COLOR + author;
        if (key.length() > Preferences.MAX_KEY_LENGTH) {
            // hash and truncate for preferences
            String hashedKey = DigestUtils.sha512Hex(key);
            key = hashedKey.substring(0, Preferences.MAX_KEY_LENGTH);
        }
        return key;
    }

    public static String convertToHex(Color color) {
        return "#"+Integer.toHexString((color.getRGB() & 0xffffff) | 0x1000000).substring(1);
    }

    public String get_color(String authorName, String authorEmail) throws XmlRpcException {
        if (authorName == null || "".equals(authorName))
            logAndThrow(10, new IllegalArgumentException("Cannot get color with NULL or empty author name"));

        Author author = new Author(authorName, authorEmail);
        LOGGER.fine("Server: getting color for author \""+author+"\" called.");

        // define random color based on randomized hue
        Color randomColor = Color.getHSBColor((float) Math.random(), 0.85f, 1.0f);
        // obtain any stored color from preferences
        Color storedColor;
        synchronized (preferences) {
            storedColor = new Color(preferences.getInt(
                    getColorKey(author),
                    randomColor.getRGB()));
        }
        // if no stored color and at least one default color left, use that one
        // then store new color for future use
        if (storedColor.equals(randomColor)) {
            synchronized (defaultColors) {
                if (!defaultColors.isEmpty()) {
                    storedColor = defaultColors.get(0);
                    defaultColors.remove(0);
                }
            }
            set_color(authorName, authorEmail, convertToHex(storedColor));
        }

        // ignore alpha channel of RGB value for storage
        return convertToHex(storedColor);
    }

    public int set_color(String authorName, String authorEmail, String hexColor) throws XmlRpcException {
        if (authorName == null || "".equals(authorName))
            logAndThrow(1, new IllegalArgumentException("Cannot set color with NULL or empty author name"));

        Author author = new Author(authorName, authorEmail);
        LOGGER.fine("Server: setting color for author \""+author+"\" to " + hexColor + ".");

        synchronized (preferences) {
            try {
                preferences.putInt(
                        getColorKey(author),
                        Color.decode(hexColor).getRGB());
                preferences.flush();
            } catch (NumberFormatException e) {
                logAndThrow(2, e);
            } catch (BackingStoreException e) {
                logAndThrow(3, e);
            }
        }
        return 0;
    }

    public int reset_color(String authorName, String authorEmail) throws XmlRpcException {
        if (authorName == null || "".equals(authorName))
            logAndThrow(1, new IllegalArgumentException("Cannot reset color with NULL or empty author name"));
        synchronized (preferences) {
            preferences.remove(getColorKey(new Author(authorName, authorEmail)));
        }
        return 0;
    }

    public int reset_all_colors() throws XmlRpcException {
        synchronized (preferences) {
            try {
                for (String key : preferences.keys()) {
                    if (key.startsWith(KEY_COLOR))
                        preferences.remove(key);
                }
            } catch (BackingStoreException e) {
                logAndThrow(1, e);
            }
        }
        return 0;
    }
    // --- end API implementation

    private void addSimpleTextNode(Document document, Element parent, String elementName, String elementText,
                                   Map<String,String> attributes) {
        Element element = document.createElement(elementName);
        parent.appendChild(element);

        if (attributes != null)
            for (Map.Entry<String,String> e : attributes.entrySet())
                element.setAttribute(e.getKey(), e.getValue());

        org.w3c.dom.Text text = document.createTextNode(elementText);
        element.appendChild(text);
    }

    private void addAuthor(Document document, Element parent, String elementName, Author author) {
        Element authorElement = document.createElement(elementName);
        parent.appendChild(authorElement);

        addSimpleTextNode(document, authorElement, "name", author.name, null);
        addSimpleTextNode(document, authorElement, "email", author.email, null);
    }

    private void addAuthorLimit(Document document, Element parent, String elementName, Set<Author> authorsList) {
        Element element = document.createElement(elementName);
        parent.appendChild(element);

        for (Author author : authorsList) {
            addAuthor(document, element, "author", author);
        }
    }

    // assumes: the directory exists already
    private void create_bug_report_xml(int sessionID, String message, File bundle, String outputFileNameAndPath) throws XmlRpcException {
        LOGGER.fine("Server: creating XML report as " + outputFileNameAndPath);

        Session session = getSession(sessionID);
        assert session != null;

        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder documentBuilder = null;
        try {
            documentBuilder = documentBuilderFactory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            logAndThrow(2, e);
        }

        assert documentBuilder != null;
        org.w3c.dom.Document document = documentBuilder.newDocument();

        Element rootElement = document.createElement("bug-report");
        document.appendChild(rootElement);

        addSimpleTextNode(document, rootElement, "user-message", message, null);
        // TODO: this is probably not relative any more! need to check
        addSimpleTextNode(document, rootElement, "relative-file-path", session.getTrackedFile().getFile().getPath(), null);

        // version information
        {
            Element element = document.createElement("build-properties");
            rootElement.appendChild(element);

            Properties properties = CommonUtils.getBuildProperties();
            Map<String,String> attributes = new HashMap<String, String>();
            for (String name : properties.stringPropertyNames()) {
                attributes.put("key", name);
                addSimpleTextNode(document, element, "entry", properties.getProperty(name), attributes);
            }
        }

        // filters
        {
            Element element = document.createElement("filters");
            rootElement.appendChild(element);

            addSimpleTextNode(document, element, "revision", session.getLimitRev(), null);
            addSimpleTextNode(document, element, "date", session.getLimitDate(), null);
            addAuthorLimit(document, element, "authors", session.getLimitedAuthors());
        }

        // active-revisions
        {
            Element element = document.createElement("active-revisions");
            rootElement.appendChild(element);

            LimitedHistory history = null;
            try {
                history = session.createLimitedHistory(get_bool_pref(BoolPrefs.COLLAPSE_AUTHORS.name()));
            } catch (Exception e) {
                logAndThrow(3, e);
            }

            assert history != null;
            for (HistoryUnit unit : history.getHistoryUnits()) {
                addSimpleTextNode(document, element, "sha", unit.revision, null);
            }
        }

        // show-options
        {
            Element element = document.createElement("show-options");
            rootElement.appendChild(element);

            for (BoolPrefs boolPref : BoolPrefs.values()) {
                Element optionElement = document.createElement("show-option");
                element.appendChild(optionElement);

                addSimpleTextNode(document, optionElement, "key", boolPref.name(), null);
                addSimpleTextNode(document, optionElement, "value", Boolean.toString(get_bool_pref(boolPref.name())), null);
            }
        }

        // bundle name (if any):
        if (bundle != null)
            addSimpleTextNode(document, rootElement, "bundle-name", bundle.getName(), null);

        // create XML file:

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = null;
        try {
            transformer = transformerFactory.newTransformer();
        } catch (TransformerConfigurationException e) {
            logAndThrow(4, e);
        }
        assert transformer != null;

        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");

        FileWriter fileWriter = null;
        try {
            File file = new File(outputFileNameAndPath);
            fileWriter = new FileWriter(file, false);
        } catch (IOException e) {
            logAndThrow(5, e);
        }
        assert fileWriter != null;

        StreamResult streamResult = new StreamResult(fileWriter);
        Source source = new DOMSource(document);
        try {
            transformer.transform(source, streamResult);

            fileWriter.flush();
            fileWriter.close();
        } catch (TransformerException e) {
            logAndThrow(6, e);
        } catch (IOException e) {
            logAndThrow(7, e);
        }
    }
}
