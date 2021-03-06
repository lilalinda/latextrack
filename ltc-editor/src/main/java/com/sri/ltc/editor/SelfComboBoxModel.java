package com.sri.ltc.editor;

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

import com.sri.ltc.filter.Author;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * @author linda
 */
public final class SelfComboBoxModel extends AbstractListModel implements ComboBoxModel {

    private static final long serialVersionUID = 2481826979689433485L;
    private final SortedSet<Author> authors = new TreeSet<Author>();
    private Author self = null;

    private final LTCSession session;

    private boolean updateLTC = true; // false to signal when NOT to update self

    public SelfComboBoxModel(LTCSession session) {
        this.session = session;
    }

    public void setSelectedItem(Object anItem) {
        int newIndex = -1;
        Author priorSelf = self;
        if (anItem instanceof Author) {
            self = (Author) anItem;
            synchronized (authors) {
                authors.add(self);
                newIndex = new ArrayList<Author>(authors).indexOf(self);
            }
        } else
            self = null;
        if ((priorSelf == null && self != null) || (priorSelf != null && !priorSelf.equals(self))) {
            // selected item has changed, so fire update
            if (newIndex != -1)
                fireContentsChanged(this, newIndex, newIndex);
            else
                fireContentsChanged(this, 0, 0);
            // update LTC accordingly (if not flagged to skip)
            if (updateLTC)
                session.setSelf(self);
            updateLTC = true; // reset flag
        }
    }

    public Object getSelectedItem() {
        return self;
    }

    public int getSize() {
        return authors.size();
    }

    public Object getElementAt(int index) {
        return new ArrayList<Author>(authors).get(index);
    }

    public void init(List<Object[]> authorList, Object[] self) {
        synchronized (authors) {
            clear();            
            if (authorList != null && !authorList.isEmpty()) {
                for (Object[] authorAsList : authorList) {
                    authors.add(Author.fromList(authorAsList));
                }
                fireIntervalAdded(this, 0, authors.size()-1);
            }
            if (self != null && self.length > 0) {
                Author author = Author.fromList(self);
                authors.add(author);
                updateLTC = false; // as self is coming from init, we don't need to update LTC
                setSelectedItem(author);
            } else
                setSelectedItem(null);
        }
    }

    public void clear() {
        synchronized (authors) {
            int size = authors.size();
            authors.clear();
            if (size > 0)
                fireIntervalRemoved(this, 0, size-1);
        }
        setSelectedItem(null);
    }
}
