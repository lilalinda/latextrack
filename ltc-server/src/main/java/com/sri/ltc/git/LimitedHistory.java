/**
 ************************ 80 columns *******************************************
 * FileHistory
 *
 * Created on Jul 24, 2010.
 *
 * Copyright 2009-2010, SRI International.
 */
package com.sri.ltc.git;

import com.sri.ltc.filter.Author;
import com.sri.ltc.latexdiff.ReaderWrapper;
import com.sri.ltc.latexdiff.StringReaderWrapper;
import edu.nyu.cs.javagit.api.GitFile;
import edu.nyu.cs.javagit.api.JavaGitException;
import edu.nyu.cs.javagit.api.options.GitRevParseOptions;
import edu.nyu.cs.javagit.api.responses.GitLogResponse;
import edu.nyu.cs.javagit.client.Factory;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.logging.Logger;

/**
 * @author linda
 */
public final class LimitedHistory extends FileHistory {

    private final static Logger LOGGER = Logger.getLogger(LimitedHistory.class.getName());
    private final Set<Author> limitingAuthors;
    private final String limitingDate;
    private final String limitingRev;

    public LimitedHistory(GitFile gitFile, Set<Author> limitingAuthors, String limitingDate, String limitingRev)
            throws IOException, ParseException, JavaGitException {
        super(gitFile);
        this.limitingAuthors = limitingAuthors;
        this.limitingDate = limitingDate;
        this.limitingRev = limitingRev;
        update();
    }

    @Override
    List<GitLogResponse.Commit> updateCommits() throws ParseException, IOException, JavaGitException {
        // perform git log:
        //   with limiting date, if applicable
        options.setOptLimitSince(null);
        if (limitingDate != null && !"".equals(limitingDate))
            options.setOptLimitSince(limitingDate);
        //   with limiting revision, if applicable
        options.setOptSince(null); // default setting
        if (limitingRev != null && !"".equals(limitingRev)) {
            // verify that limiting rev points to valid revision
            GitRevParseOptions revParseOptions = new GitRevParseOptions();
            revParseOptions.setVerify(true);
            try {
                List<String> revParseOutput =
                        Factory.createGitRevParse().revParse(gitFile.getWorkingTree().getPath(),
                                revParseOptions,
                                limitingRev+"^");
                options.setOptSince(revParseOutput.get(0));
            } catch (JavaGitException e) {
                // if "git rev-parse --verify limitingRev^" throws an exception,
                // the specified revision doesn't exist or limitingRev might be the first
                // (and doesn't have a parent that "^" would point to);
                // simply ignore this setting then to not limit the following "git log" command
            }
        }
        return getLog(options, "limited");
    }

    @Override
    void transformGraph() {
        // TODO: implement
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
    void transformList() throws IOException, JavaGitException {
        Author self = new Self(gitFile).getSelf();

        // reduce commit graph to authors
        if (limitingAuthors != null && !limitingAuthors.isEmpty())
            for (ListIterator<Commit> i = commitList.listIterator(); i.hasNext(); )
                if (!limitingAuthors.contains(i.next().author))
                    i.remove();

        // collapse sequences of same author
        if (commitList.size() > 0) {
            Author currentAuthor = commitList.get(0).author;
            for (ListIterator<Commit> i = commitList.listIterator(1); i.hasNext(); ) {
                Author a = i.next().author;
                if (a.equals(currentAuthor))
                    i.remove();
                else
                    currentAuthor = a;
            }
        }

        // if no limiting date nor rev then reduce list until last commit of calling author (by name only)
        if ((limitingDate == null || "".equals(limitingDate)) &&
                (limitingRev == null || "".equals(limitingRev))) {
            int i = 0; // start with most current
            // ignore most recent commit(s) of self:
            for (; i < commitList.size() && self.name.equals(commitList.get(i).author.name); i++);
            // keep all commits from other authors
            for (; i < commitList.size() && !self.name.equals(commitList.get(i).author.name); i++);
            if (i < commitList.size())
                commitList.subList(i+1, commitList.size()).clear(); // remove all remaining commits
        }

        LOGGER.info("Transformed list for \""+gitFile.getFile().getName()+"\" to "+commitList.size()+" commits");
    }

    public final List<Commit> getCommitsList() {
        return commitList;
    }

    public final List<Author> getAuthorsList() throws IOException, ParseException, JavaGitException {
        List<Author> authors = new ArrayList<Author>();
        for (Commit commit : commitList)
            authors.add(commit.author);
        return authors;
    }

    public final List<ReaderWrapper> getReadersList() throws IOException, JavaGitException, ParseException {
        List<ReaderWrapper> readers = new ArrayList<ReaderWrapper>();
        for (Commit commit : commitList) {
            // obtain string for readers
            String contents = Factory.createGitShow().show(
                    gitFile.getWorkingTree().getPath(),
                    null,
                    commit.sha1+":"+gitFile.getRelativePath());
            readers.add(new StringReaderWrapper(contents));
        }
        return readers;
    }
}
