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
package com.sri.ltc.versioncontrol.history;

import com.sri.ltc.filter.Author;
import com.sri.ltc.latexdiff.CommitReaderWrapper;
import com.sri.ltc.latexdiff.ReaderWrapper;
import com.sri.ltc.versioncontrol.Commit;
import com.sri.ltc.versioncontrol.TrackedFile;
import com.sri.ltc.versioncontrol.VersionControlException;

import java.io.IOException;
import java.text.ParseException;
import java.util.*;
import java.util.logging.Logger;

/**
 * @author linda
 */
public final class LimitedHistory extends FileHistory {

    private final static Logger LOGGER = Logger.getLogger(LimitedHistory.class.getName());
    private final Set<Author> limitingAuthors;
    private final String limitingDate;
    private final String limitingRev;
    private final boolean collapseAuthors;

    public LimitedHistory(TrackedFile file,
                          Set<Author> limitingAuthors,
                          String limitingDate,
                          String limitingRev,
                          boolean collapseAuthors)
            throws Exception {
        super(file);
        this.limitingAuthors = limitingAuthors;
        this.limitingDate = limitingDate;
        this.limitingRev = limitingRev;
        this.collapseAuthors = collapseAuthors;
        update();
    }

    @Override
    List<Commit> updateCommits() throws ParseException, VersionControlException, IOException {
        return trackedFile.getCommits(
                ((limitingDate == null) || limitingDate.isEmpty()) ? null : Commit.deSerializeDate(limitingDate),
                ((limitingRev == null) || limitingRev.isEmpty()) ? null : limitingRev);
    }

    @Override
    void transformGraph() {
        // TODO: implement - maybe? (wasn't implemented pre git change)
//        // reduce commit graph to authors
//        commitGraph.reduceByAuthors(limitingAuthors);
//
//        // collapse sequences of same author
//        if (commitGraph.vertexSet().size() > 0) {
//            Author currentAuthor = Author.parse(gitCommits.get(0).getAuthor());
//            for (ListIterator<GitLogResponse.Commit> i = gitCommits.listIterator(1); i.hasNext(); ) {
//                Author a = Author.parse(i.next().getAuthor());
//                if (a.equals(currentAuthor))
//                    i.remove();
//                else
//                    currentAuthor = a;
//            }
//        }
//
//        // if no limiting date nor rev then reduce list until last commit of calling author (by name only)
//        if ((limitingDate == null || "".equals(limitingDate)) &&
//                (limitingRev == null || "".equals(limitingRev))) {
//            int i = 0; // start with most current
//            // ignore most recent commit(s) of self:
//            for (; i < gitCommits.size() && self.name.equals(Author.parse(gitCommits.get(i).getAuthor()).name); i++);
//            // keep all commits from other authors
//            for (; i < gitCommits.size() && !self.name.equals(Author.parse(gitCommits.get(i).getAuthor()).name); i++);
//            if (i < gitCommits.size())
//                gitCommits.subList(i+1, gitCommits.size()).clear(); // remove all remaining commits
//        }
    }

    @Override
    void transformList() throws IOException {
        Author self = trackedFile.getRepository().getSelf();

        // reduce commit path to authors (if any specified)
        if (limitingAuthors != null && !limitingAuthors.isEmpty())
            for (ListIterator<Commit> i = commitList.listIterator(); i.hasNext(); )
                if (!limitingAuthors.contains(i.next().getAuthor()))
                    i.remove();

        // if no limiting date nor rev then reduce list until last commit of calling author (by name only)
        if ((limitingDate == null || "".equals(limitingDate)) &&
                (limitingRev == null || "".equals(limitingRev))) {
            int i = 0; // start with most current
            // ignore most recent commit(s) of self:
            for (; i < commitList.size() && self.name.equals(commitList.get(i).getAuthor().name); i++);
            // keep all commits from other authors
            for (; i < commitList.size() && !self.name.equals(commitList.get(i).getAuthor().name); i++);
            if (i < commitList.size())
                commitList.subList(i+1, commitList.size()).clear(); // remove all remaining commits
        }

        // collapse sequences of same author, if requested
        if (collapseAuthors && commitList.size() > 0) {
            Author currentAuthor = commitList.get(0).getAuthor();
            for (ListIterator<Commit> i = commitList.listIterator(1); i.hasNext(); ) {
                Author a = i.next().getAuthor();
                if (a.equals(currentAuthor))
                    i.remove();
                else
                    currentAuthor = a;
            }
        }

        LOGGER.fine("Transformed list for \""+ trackedFile.getFile().getName()+"\" to "+commitList.size()+" commits");
    }

    public final List<Commit> getCommitsList() {
        return commitList;
    }

    public final List<Author> getAuthorsList() throws IOException, ParseException {
        List<Author> authors = new ArrayList<Author>();
        for (Commit commit : commitList)
            authors.add(commit.getAuthor());
        return authors;
    }

    public final List<ReaderWrapper> getReadersList() throws IOException, ParseException {
        List<ReaderWrapper> readers = new ArrayList<ReaderWrapper>();
        for (Commit commit : commitList)
            // obtain string for readers
            readers.add(new CommitReaderWrapper(commit));
        return readers;
    }

    public final List<String> getIDs() {
        List<String> ids = new ArrayList<String>();
        for (Commit commit : commitList)
            ids.add(commit.getId());
        return ids;
    }
}
