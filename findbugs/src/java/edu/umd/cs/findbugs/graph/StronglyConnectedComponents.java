/*
 * Generic graph library
 * Copyright (C) 2000,2003,2004 University of Maryland
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

// $Revision: 1.10 $

package edu.umd.cs.findbugs.graph;

import java.util.*;

/**
 * Algorithm to find strongly connected components in a graph.
 * Based on algorithm in Cormen et. al., <cite>Introduction to Algorithms</cite>,
 * p. 489.
 */
public class StronglyConnectedComponents
        <
        GraphType extends Graph<EdgeType, VertexType>,
        EdgeType extends GraphEdge<EdgeType, VertexType>,
        VertexType extends GraphVertex<VertexType>
        > {

	private ArrayList<SearchTree<VertexType>> m_stronglyConnectedSearchTreeList;
	private VertexChooser<VertexType> m_vertexChooser;

	/**
	 * Constructor.
	 */
	public StronglyConnectedComponents() {
		m_stronglyConnectedSearchTreeList = new ArrayList<SearchTree<VertexType>>();
		m_vertexChooser = null;
	}

	/**
	 * Specify a VertexChooser object to restrict which vertices are
	 * considered.  This is useful if you only want to find strongly
	 * connected components among a particular category of vertices.
	 */
	public void setVertexChooser(VertexChooser<VertexType> vertexChooser) {
		m_vertexChooser = vertexChooser;
	}

	/**
	 * Find the strongly connected components in given graph.
	 *
	 * @param g       the graph
	 * @param toolkit a GraphToolkit, used to create temporary graphs
	 *                used by the algorithm
	 */
	public void findStronglyConnectedComponents(GraphType g,
	                                            GraphToolkit<GraphType, EdgeType, VertexType> toolkit) {

		// Perform the initial depth first search
		DepthFirstSearch<GraphType, EdgeType, VertexType> initialDFS =
		        new DepthFirstSearch<GraphType, EdgeType, VertexType>();
		if (m_vertexChooser != null)
			initialDFS.setVertexChooser(m_vertexChooser);
		initialDFS.search(g);

		// Create a transposed graph
		Transpose<GraphType, EdgeType, VertexType> t =
		        new Transpose<GraphType, EdgeType, VertexType>();
		GraphType transpose = t.transpose(g, toolkit);

		// Create a set of vertices in the transposed graph,
		// in descending order of finish time in the initial
		// depth first search.
		VisitationTimeComparator<VertexType> comparator =
		        new VisitationTimeComparator<VertexType>(initialDFS.getFinishTimeList(),
		                VisitationTimeComparator.DESCENDING);
		Set<VertexType> descendingByFinishTimeSet = new TreeSet<VertexType>(comparator);
		Iterator<VertexType> i = transpose.vertexIterator();
		while (i.hasNext()) {
			descendingByFinishTimeSet.add(i.next());
		}

		// Now perform a DFS on the transpose, choosing the vertices
		// to visit in the main loop by descending finish time
		DepthFirstSearch<GraphType, EdgeType, VertexType> transposeDFS =
		        new DepthFirstSearch<GraphType, EdgeType, VertexType>();
		if (m_vertexChooser != null)
			transposeDFS.setVertexChooser(m_vertexChooser);
		transposeDFS.search(transpose, descendingByFinishTimeSet.iterator());

		// The search tree roots of the second DFS represent the
		// strongly connected components.  Note that we call copySearchTree()
		// to make the returned search trees relative to the original
		// graph, not the transposed graph (which would be very confusing).
		Iterator<SearchTree<VertexType>> j = transposeDFS.searchTreeIterator();
		while (j.hasNext()) {
			m_stronglyConnectedSearchTreeList.add(copySearchTree(j.next(), t));
		}
	}

	/**
	 * Make a copy of given search tree (in the transposed graph)
	 * using vertices of the original graph.
	 *
	 * @param tree a search tree in the transposed graph
	 * @param t    the Transpose object which performed the transposition of
	 *             the original graph
	 */
	private SearchTree<VertexType> copySearchTree(SearchTree<VertexType> tree,
	                                              Transpose<GraphType, EdgeType, VertexType> t) {
		// Copy this node
		SearchTree<VertexType> copy = new SearchTree<VertexType>(t.getOriginalGraphVertex(tree.getVertex()));

		// Copy children
		// FIXME: Eclipse/Cheetah lossage.
		// It complains "Type mismatch: cannot convert from Iterator<SearchTree<VertexType>>
		// to Iterator<SearchTree<VertexType>>".
		// Changed to use raw types.
//		Iterator<SearchTree<VertexType>> i = tree.childIterator();
		Iterator i = tree.childIterator();
		while (i.hasNext()) {
			SearchTree<VertexType> child = (SearchTree<VertexType>) i.next();
			copy.addChild(copySearchTree(child, t));
		}

		return copy;
	}

	/**
	 * Returns an iterator over the search trees containing the
	 * vertices of each strongly connected component.
	 *
	 * @return an Iterator over a sequence of SearchTree objects
	 */
	public Iterator<SearchTree<VertexType>> searchTreeIterator() {
		return m_stronglyConnectedSearchTreeList.iterator();
	}

	/**
	 * Iterator for iterating over sets of vertices in
	 * strongly connected components.
	 */
	private class SCCSetIterator implements Iterator<Set<VertexType>> {
		private Iterator<SearchTree<VertexType>> m_searchTreeIterator;

		public SCCSetIterator() {
			m_searchTreeIterator = searchTreeIterator();
		}

		public boolean hasNext() {
			return m_searchTreeIterator.hasNext();
		}

		public Set<VertexType> next() {
			SearchTree<VertexType> tree = m_searchTreeIterator.next();
			TreeSet<VertexType> set = new TreeSet<VertexType>();
			tree.addVerticesToSet(set);
			return set;
		}

		public void remove() {
			throw new UnsupportedOperationException();
		}
	}

	/**
	 * Returns an iterator over the sets of vertices
	 * of each strongly connected component.
	 *
	 * @return an Iterator over a sequence of Set objects
	 */
	public Iterator<Set<VertexType>> setIterator() {
		return new SCCSetIterator();
	}

}

// vim:ts=4
