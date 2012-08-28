package com.sri.ltc.versioncontrol.git;

import com.sri.ltc.filter.Author;
import com.sri.ltc.server.LTCserverInterface;
import com.sri.ltc.versioncontrol.Commit;
import com.sri.ltc.versioncontrol.Repository;
import com.sri.ltc.versioncontrol.TrackedFile;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class GitCommit extends Commit<GitRepository> {
    private RevCommit revCommit;
    private GitTrackedFile trackedFile;

    public static Date CommitDate(RevCommit revCommit) {
        return new Date(revCommit.getCommitTime() * 1000L);
    }
    
    public GitCommit(GitRepository repository, GitTrackedFile trackedFile, RevCommit revCommit) {
        super(repository);
        this.revCommit = revCommit;
        this.trackedFile = trackedFile;
    }

    @Override
    public String getId() {
        return revCommit.getId().name();
    }

    @Override
    public String getMessage() {
        return revCommit.getFullMessage();
    }

    @Override
    public Author getAuthor() {
        return new Author(revCommit.getAuthorIdent().getName(), revCommit.getAuthorIdent().getEmailAddress(), null);
    }

    @Override
    public Date getDate() {
        return GitCommit.CommitDate(revCommit);
    }

    @Override
    public InputStream getContentStream() throws IOException {
        if (trackedFile == null) return null;
        
        TreeWalk treeWalk = TreeWalk.forPath(getRepository().getWrappedRepository(), trackedFile.getRepositoryRelativeFilePath(), revCommit.getTree());
        ObjectId objectId = treeWalk.getObjectId(0);
        ObjectLoader loader = getRepository().getWrappedRepository().open(objectId);

        return loader.openStream();
    }

    @Override
    public List<Commit<GitRepository>> getParents() {
        List<Commit<GitRepository>> parents = new ArrayList<Commit<GitRepository>>();
        for (RevCommit parentCommit : revCommit.getParents()) {
            parents.add(new GitCommit(getRepository(), trackedFile, parentCommit));
        }

        return parents;
    }
}
