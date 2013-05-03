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

import com.google.common.collect.Sets;
import com.sri.ltc.server.LTCserverInterface;

import javax.swing.table.AbstractTableModel;
import java.text.ParseException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author linda
 */
public final class CommitTableModel extends AbstractTableModel {

    private final static Logger LOGGER = Logger.getLogger(CommitTableModel.class.getName());
    private final List<CommitTableRow> commits = new ArrayList<CommitTableRow>();
    private CommitTableRow firstCell = null;

    private static final long serialVersionUID = -923583506868039590L;

    @Override
    public int getColumnCount() {
        return CommitTableRow.COLUMN_NAMES.length;
    }

    @Override
    public String getColumnName(int i) {
        return CommitTableRow.COLUMN_NAMES[i];
    }

    @Override
    public Class<?> getColumnClass(int i) {
        return CommitTableRow.getColumnClass(i);
    }

    @Override
    public int getRowCount() {
        synchronized (commits) {
            return commits.size()+(firstCell==null?0:1);
        }
    }

    @Override
    public Object getValueAt(int row, int column) {
        return getRow(row).getColumn(column);
    }

    private CommitTableRow getRow(int row) {
        synchronized (commits) {
            if (firstCell == null)
                return commits.get(row);
            if (row == 0)
                return firstCell;
            return commits.get(row - 1);
        }
    }

    public void init(List<Object[]> rawCommits, boolean clearFirstCell) {
        synchronized (commits) {
            clear(clearFirstCell);

            if (rawCommits == null)
                return;

            // 1) build up list of commits from given list
            Map<String,CommitTableRow> commitMap = new HashMap<String,CommitTableRow>();
            for (Object[] c : rawCommits) {
                try {
                    CommitTableRow ctr = new CommitTableRow(c);
                    commits.add(ctr);
                    commitMap.put(c[0].toString(), ctr); // remember in map for later
                } catch (ParseException e) {
                    LOGGER.log(Level.SEVERE, e.getMessage(), e);
                }
            }
            // 2) build up graph structure going through given list a second time
            for (Object[] c : rawCommits) {
                CommitTableRow node = commitMap.get(c[0].toString());
                String[] tokens = c[5].toString().split("\\s+");
                for (String ID : tokens) {
                    CommitTableRow parent = commitMap.get(ID);
                    if (parent != null) {
                        node.parents.add(parent);
                        parent.children.add(node);
                    }
                }
            }
            // 3) update graph column locations
            commits.get(0).graph.circleColumn = 0; // start with first column
            SortedSet<Integer> currentColumns = new TreeSet<Integer>(); // keep track of passing lines
            for (CommitTableRow node : commits) {
                // update current columns based on incoming set
                currentColumns.removeAll(node.graph.incomingColumns); // remove all incoming columns...
                currentColumns.add(node.graph.circleColumn); // ... except the current column
                // set of passing lines is difference currentColumns\{circleColumn}
                node.graph.passingColumns.clear();
                Sets.difference(currentColumns, Collections.singleton(node.graph.circleColumn))
                        .copyInto(node.graph.passingColumns);
                // determine columns of parents:
                for (CommitTableRow parent : node.parents) {
                    // find lowest that is neither in outgoing columns nor in passing columns
                    SortedSet<Integer> union = new TreeSet<Integer>();
                    Sets.union(node.graph.outgoingColumns, node.graph.passingColumns).copyInto(union);
                    int lowest = getLowestNotIn(union, 0);
                    if (parent.graph.circleColumn == Integer.MAX_VALUE) {
                        parent.graph.circleColumn = lowest;
                    } else {
                        // see if we should move the parent further left
                        if (lowest < parent.graph.circleColumn) {
                            currentColumns.remove(parent.graph.circleColumn); // no more passing there
                            parent.graph.circleColumn = lowest;
                        }
                    }
                    // maintain current columns
                    currentColumns.add(parent.graph.circleColumn);
                    // update incoming columns of parent
                    parent.graph.incomingColumns.add(parent.graph.circleColumn); // add current column to incoming
                    // update outgoing columns of node
                    node.graph.outgoingColumns.add(parent.graph.circleColumn); // TODO: is this correct?
                }
                // maintain current columns: merges are when...
                if (!node.graph.outgoingColumns.contains(node.graph.circleColumn))
                    currentColumns.remove(node.graph.circleColumn); // merge
            }
            fireTableDataChanged();
        }
    }

    private int getLowestNotIn(Set<Integer> set, int start) {
        int lowest = start;
        for (; set.contains(lowest); lowest++) {}
        return lowest;
    }

    public void update(Set<String> IDs) {
        synchronized (commits) {
            firstCell = null;
            if (IDs.contains(LTCserverInterface.ON_DISK)) {
                initFirstCell(LTCserverInterface.ON_DISK);
            }
            if (IDs.contains(LTCserverInterface.MODIFIED)) {
                initFirstCell(LTCserverInterface.MODIFIED);
            }
            for (CommitTableRow row : commits)
                row.setActive(IDs.contains(row.ID));
        }
        fireTableDataChanged();
    }

    public void add(Object[] last_commit) {
        if (last_commit != null)
            synchronized (commits) {
                try {
                    commits.add(0, new CommitTableRow(last_commit));
                    // TODO: update graph columns etc.
                    int insertedRow = 0 + (firstCell==null?0:1);
                    fireTableRowsInserted(insertedRow, insertedRow);
                } catch (ParseException e) {
                    LOGGER.log(Level.SEVERE, e.getMessage(), e);
                }
            }
    }

    // return true if something changed
    private void initFirstCell(String ID) {
        boolean updated = firstCell != null && !firstCell.ID.equals(ID);
        if (firstCell == null || updated)
            firstCell = new CommitTableRow(ID);
        if (updated)
            fireTableRowsUpdated(0, 0);
        else
            fireTableRowsInserted(0, 0);
    }

    public void addOnDisk() {
        initFirstCell(LTCserverInterface.ON_DISK);
    }

    public void removeOnDisk() {
        if (firstCell != null && firstCell.ID.equals(LTCserverInterface.ON_DISK)) {
            firstCell = null;
            fireTableRowsDeleted(0, 0);
        }
    }

    public void updateFirst(String ID) {
        initFirstCell(ID);
    }

    public void clear(boolean clearFirstCell) {
        synchronized (commits) {
            int size = getRowCount();
            commits.clear();
            if (clearFirstCell)
                firstCell = null;
            if (size > 0)
                fireTableRowsDeleted(firstCell==null?0:1, size-1);
        }
    }

    public boolean isActive(int row) {
        if (row < 0)
            return false;
        return getRow(row).isActive();
    }
}
