/*
 * #%L
 * LaTeX Track Changes (LTC) allows collaborators on a version-controlled LaTeX writing project to view and query changes in the .tex documents.
 * %%
 * Copyright (C) 2009 - 2014 SRI International
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
package com.sri.ltc.editor;

import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.sri.ltc.filter.Author;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;

/**
 * Small panel with currently selected authors for limiting.
 *
 * The authors are displayed in alphabetical order.  They can be added, removed, or reset (remove all).
 *
 * @author linda
 */
public final class AuthorPanel extends JPanel {

    private static int HEIGHT;
    private final SortedSet<Author> model = new TreeSet<Author>();
    private final AuthorListModel authorModel;
    private final Set<ChangeListener> listeners = Sets.newHashSet();

    public AuthorPanel(Color background, AuthorListModel authorModel) {
        super(); // using FLowLayout!
        this.authorModel = authorModel;

        // add one author label temporarily to calculate height:
        Component c = add(new AuthorLabel(new Author("A", null)));
        HEIGHT = super.getPreferredSize().height;
        remove(c);

        setBackground(background);
    }

    @Override
    public Dimension getPreferredSize() {
        int width = 0;
        if (getComponents().length > 0)
            width = super.getPreferredSize().width;
        return new Dimension(width, HEIGHT);
    }

    public void addChangeListener(ChangeListener l) {
        listeners.add(l);
    }

    public Set<String> dataAsStrings() {
        synchronized (model) {
            return Sets.newHashSet(Collections2.transform(Sets.newHashSet(model),
                    new Function<Author, String>() {
                        @Override
                        public String apply(Author author) {
                            return author.toString();
                        }
                    }));
        }
    }

    public List<Object[]> dataAsList() {
        synchronized (model) {
            return Lists.newArrayList(Collections2.transform(Sets.newHashSet(model),
                    new Function<Author, Object[]>() {
                        @Override
                        public Object[] apply(Author author) {
                            return author.asList();
                        }
                    }));
        }
    }

    public boolean addAuthor(Author author) {
        synchronized (model) {
            boolean result = model.add(author);
            if (result)
                update();
            return result;
        }
    }

    private boolean removeAuthor(Author author) {
        synchronized (model) {
            boolean result = model.remove(author);
            if (result)
                update();
            return result;
        }
    }

    private void update() {
        synchronized (model) {
            // remove all components and add them in alphabetical order
            removeAll();
            for (Iterator<Author> i = model.iterator(); i.hasNext(); )
                add(new AuthorLabel(i.next()));
            setSize(getPreferredSize()); // copying what CompoundBorder does...
            revalidate();
            // notify listeners:
            for (ChangeListener l : listeners)
                l.stateChanged(new ChangeEvent(this));
        }
    }

    private class AuthorLabel extends JLabel {

        public AuthorLabel(final Author author) {
            super(author.name, new RemoveIcon(), JLabel.CENTER);
            setHorizontalTextPosition(JLabel.LEADING);
            setForeground(authorModel.getColorForAuthor(author));

            // react to clicks by deleting from panel
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    AuthorPanel.this.removeAuthor(author);
                }
            });
            setToolTipText("<html>Click to remove<br>\""+author.toHTML()+"\"</html>");

            // draw a little thin line border and then some space
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(CommitTableRenderer.INACTIVE_COLOR, 1),
                    BorderFactory.createEmptyBorder(0, 4, 0, 4)));
        }
    }

    private class RemoveIcon implements Icon {

        private final static int LENGTH = 8; // pixels for square

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            // setup drawing
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setStroke(new BasicStroke(1f));
            g2d.setColor(CommitTableRenderer.INACTIVE_COLOR);

            // draw cross
            g2d.drawLine(x, y, x+LENGTH, y+LENGTH);
            g2d.drawLine(x, y+LENGTH, x+LENGTH, y);

            // draw enclosing border
            g2d.drawRect(x, y, LENGTH, LENGTH);
        }

        @Override
        public int getIconWidth() {
            return LENGTH;
        }

        @Override
        public int getIconHeight() {
            return LENGTH;
        }
    }
}
