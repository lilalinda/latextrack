/**
 ************************ 80 columns *******************************************
 * LTCWorker
 *
 * Created on Oct 11, 2010.
 *
 * Copyright 2009-2010, SRI International.
 */
package com.sri.ltc.viewer;

import com.sri.ltc.ProgressReceiver;
import com.sri.ltc.server.LTCserverImpl;
import com.sri.ltc.server.LTCserverInterface;
import org.apache.xmlrpc.XmlRpcException;

import javax.swing.*;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author linda
 */
public abstract class LTCWorker<T,V> extends SwingWorker<T,V> implements ProgressReceiver {

    protected final Logger LOGGER = Logger.getLogger(LTCWorker.class.getName());
    protected final GitViewer gitViewer;
    protected final LTCserverInterface LTC = new LTCserverImpl(this);
    protected final int sessionID;

    protected LTCWorker(GitViewer gitViewer, int sessionID, String progressTitle, String progressText, boolean withCancel) {
        this.gitViewer = gitViewer;
        this.sessionID = sessionID;
        // initialize progress
        ProgressDialog.showDialog(this.gitViewer, progressTitle, progressText, withCancel?this:null);
        addPropertyChangeListener(new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent e) {
                if ("progress".equals(e.getPropertyName())) {
                    ProgressDialog.setProgress((Integer) e.getNewValue());
                }
                if ("state".equals(e.getPropertyName()) && SwingWorker.StateValue.DONE == e.getNewValue()) {
                    ProgressDialog.done();
                }
            }
        });
    }

    @Override
    protected final T doInBackground() throws Exception {
        try {
            return callLTCinBackground();
        } catch (XmlRpcException e) {
            LOGGER.log(Level.SEVERE, e.getMessage(), e);
            throw e;
        }
    }

    protected abstract T callLTCinBackground() throws XmlRpcException;

    @Override
    public final void updateProgress(int progress) {
        setProgress(progress);
    }
}
