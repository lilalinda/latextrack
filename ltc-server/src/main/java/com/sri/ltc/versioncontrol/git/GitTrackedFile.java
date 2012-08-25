package com.sri.ltc.versioncontrol.git;

import com.sri.ltc.versioncontrol.Commit;
import com.sri.ltc.versioncontrol.TrackedFile;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class GitTrackedFile extends TrackedFile<GitRepository> {
    public GitTrackedFile(GitRepository repository, File file) {
        super(repository, file);
    }

    @Override
    public List<Commit> getCommits() throws IOException {
        // note: we could use the simpler LogCommand with add + addPath, but that
        // throws a jgit-specific exception.

        List<Commit> commits = new ArrayList<Commit>();

        Repository wrappedRepository = getRepository().getWrappedRepository();
        RevWalk revWalk = new RevWalk(wrappedRepository);
        revWalk.setTreeFilter(
                AndTreeFilter.create(
                        PathFilterGroup.createFromStrings(getRepositoryRelativeFilePath()),
                        TreeFilter.ANY_DIFF)
        );

        {
            RevCommit rootCommit = revWalk.parseCommit(wrappedRepository.resolve(Constants.HEAD));
            revWalk.sort(RevSort.COMMIT_TIME_DESC);
            revWalk.markStart(rootCommit);
        }

        for (RevCommit revCommit : revWalk) {
            commits.add(new GitCommit(getRepository(), revCommit));

//            TreeWalk treeWalk = TreeWalk.forPath(wrappedRepository, getRepositoryRelativeFilePath(), revCommit.getTree());
//            if (treeWalk != null) {
//                treeWalk.setRecursive(true);
//                treeWalk.setFilter(
//                        AndTreeFilter.create(
//                                PathFilterGroup.createFromStrings(getRepositoryRelativeFilePath()), TreeFilter.ANY_DIFF)
//                );
//
//                CanonicalTreeParser canonicalTreeParser = treeWalk.getTree(0, CanonicalTreeParser.class);
//
//                while (!canonicalTreeParser.eof()) {
//                    System.out.println("- found entry: " + canonicalTreeParser.getEntryPathString());
//                    canonicalTreeParser.next(1);
//                }
//            }
        }

        return commits;
    }

    @Override
    public Status getStatus() throws IOException {
        org.eclipse.jgit.lib.Repository wrappedRepository = getRepository().getWrappedRepository();

        IndexDiff diff = new IndexDiff(wrappedRepository, Constants.HEAD, new FileTreeIterator(wrappedRepository));
        diff.setFilter(PathFilter.create(getRepositoryRelativeFilePath()));
        diff.diff();

        if (!diff.getAdded().isEmpty())
            return Status.Added;
        if (!diff.getChanged().isEmpty())
            return Status.Changed;
        if (!diff.getConflicting().isEmpty())
            return Status.Conflicting;
        if (!diff.getIgnoredNotInIndex().isEmpty())
            return Status.Ignored;
        if (!diff.getMissing().isEmpty())
            return Status.Missing;
        if (!diff.getModified().isEmpty())
            return Status.Modified;
        if (!diff.getRemoved().isEmpty())
            return Status.Removed;
        if (!diff.getUntracked().isEmpty())
            return Status.NotTracked;

        // well, if it's none of these things, then it must be in the index and happy
        return Status.Unchanged;
    }

    private String getRepositoryRelativeFilePath() {
        String basePath = getRepository().getWrappedRepository().getWorkTree().getPath();
        return new File(basePath).toURI().relativize(getFile().toURI()).getPath();
    }
}
