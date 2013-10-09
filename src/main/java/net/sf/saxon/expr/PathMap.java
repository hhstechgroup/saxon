package net.sf.saxon.expr;

import net.sf.saxon.Configuration;
import net.sf.saxon.functions.Doc;
import net.sf.saxon.functions.Document;
import net.sf.saxon.functions.SystemFunction;
import net.sf.saxon.om.Axis;
import net.sf.saxon.pattern.AnyNodeTest;
import net.sf.saxon.pattern.NodeKindTest;
import net.sf.saxon.query.StaticQueryContext;
import net.sf.saxon.query.XQueryExpression;
import net.sf.saxon.sxpath.XPathEvaluator;
import net.sf.saxon.sxpath.XPathExpression;
import net.sf.saxon.trans.XPathException;

import java.io.File;
import java.io.FileReader;
import java.io.PrintStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

/**
 * A PathMap is a description of all the paths followed by an expression.
 * It is a set of trees. Each tree contains as its root an expression that selects
 * nodes without any dependency on the context. The arcs in the tree are axis steps.
 * So the expression doc('a.xml')/a[b=2]/c has a single root (the call on doc()), with
 * a single arc representing child::a, this leads to a node which has two further arcs
 * representing child::b and child::c. Because element b is atomized, there will also be
 * an arc for the step descendant::text() indicating the requirement to access the text
 * nodes of the element.
 *
 * <p>The current implementation works only for XPath 2.0 expressions (for example, constructs
 * like xsl:for-each-group are not handled.)</p>
 *
 * <p>This class, together with the overloaded method
 * {@link Expression#addToPathMap(PathMap, net.sf.saxon.expr.PathMap.PathMapNodeSet)} can be
 * seen as an implementation of the static path analysis algorithm given in section 4 of
 * <a href="http://www-db.research.bell-labs.com/user/simeon/xml_projection.pdf">A. Marian and J. Simeon,
 * Projecting XML Documents, VLDB 2003</a>.</p>
 */

public class PathMap {

    private List pathMapRoots = new ArrayList();        // a list of PathMapRoot objects
    private HashMap pathsForVariables = new HashMap();  // a map from a variable Binding to a PathMapNodeSet

    /**
     * A node in the path map. A node holds a set of arcs, each representing a link to another
     * node in the path map.
     */

    public static class PathMapNode {
        List arcs;              // a list of PathMapArcs
        private boolean returnable;
        private boolean atomized;
        private boolean hasUnknownDependencies;

        /**
         * Create a node in the PathMap (initially with no arcs)
         */

        private PathMapNode() {
            arcs = new ArrayList();
        }

        /**
         * Create a new arc
         * @param step the AxisExpression representing this step
         * @return the newly-constructed target of the new arc
         */

        public PathMapNode createArc(AxisExpression step) {
            for (int i=0; i<arcs.size(); i++) {
                PathMapArc a = (PathMapArc)arcs.get(i);
                if (a.getStep().equals(step)) {
                    return a.getTarget();
                }
            }
            PathMapNode target = new PathMapNode();
            PathMapArc arc = new PathMapArc(step, target);
            arcs.add(arc);
            return target;
        }

        /**
         * Create a new arc to an existing target
         * @param step the AxisExpression representing this step
         * @param target the target node of the new arc
         */

        public void createArc(AxisExpression step, PathMapNode target) {
            for (int i=0; i<arcs.size(); i++) {
                PathMapArc a = (PathMapArc)arcs.get(i);
                if (a.getStep().equals(step) && a.getTarget() == target) {
                    // TODO: if it's a different target, then merge the two targets into one. XMark Q8
                    a.getTarget().setReturnable(a.getTarget().isReturnable() || target.isReturnable());
                    if (target.isAtomized()) {
                        a.getTarget().setAtomized();
                    }
                    return;
                }
            }
            PathMapArc arc = new PathMapArc(step, target);
            arcs.add(arc);
        }

        /**
         * Get the arcs emanating from this node in the PathMap
         * @return the arcs, each representing an AxisStep. The order of arcs in the array is undefined.
         */

        public PathMapArc[] getArcs() {
            return (PathMapArc[])arcs.toArray(new PathMapArc[arcs.size()]);
        }

        /**
         * Indicate that the node represents a value that is returnable as the result of the
         * supplied expression, rather than merely a node that is visited en route
         * @param returnable true if the node represents a final result of the expression
         */

        public void setReturnable(boolean returnable) {
            this.returnable = true;
        }

        /**
         * Ask whether the node represents a value that is returnable as the result of the
         * supplied expression, rather than merely a node that is visited en route
         * @return true if the node represents a final result of the expression
         */

        public boolean isReturnable() {
            return returnable;
        }

        /**
         * Indicate that the typed value or string value of the node reached by this path
         * will be used.
         */

        public void setAtomized() {
            this.atomized = true;
        }

        /**
         * Ask whether the typed value (or string value) of the node reached by this path
         * will be required.
         * @return true if the typed value or string value of the node is required
         */

        public boolean isAtomized() {
            return atomized;
        }

        /**
         * Indicate that the path has unknown dependencies, typically because a node reached
         * by the path is supplied as an argument to a user-defined function
         */

        public void setHasUnknownDependencies() {
            hasUnknownDependencies = true;
        }

        /**
         * Ask whether the path has unknown dependencies, typically because a node reached
         * by the path is supplied as an argument to a user-defined function
         * @return true if the path has unknown dependencies
         */

        public boolean hasUnknownDependencies() {
            return hasUnknownDependencies;
        }
    }

    /**
     * A root node in the path map. A root node represents either (a) a subexpression that is the first step in
     * a path expression, or (b) a subexpression that is not the first step in a path, but which returns nodes
     * (for example, a call on the doc() function).
     */

    public static class PathMapRoot extends PathMapNode {

        private Expression rootExpression;
        private boolean isDownwardsOnly;

        /**
         * Create a PathMapRoot
         * @param root the expression at the root of a path
         */
        private PathMapRoot(Expression root) {
            this.rootExpression = root;
        }

        /**
         * Get the root expression
         * @return the expression at the root of the path
         */
        public Expression getRootExpression() {
            return rootExpression;
        }

    }

    /**
     * An arc joining two nodes in the path map. The arc has a target (destination) node, and is
     * labelled with an AxisExpression representing a step in a path expression
     */

    public static class PathMapArc {
        private PathMapNode target;
        private AxisExpression step;

        /**
         * Create a PathMapArc
         * @param step the axis step, represented by an AxisExpression
         * @param target the node reached by following this arc
         */
        private PathMapArc(AxisExpression step, PathMapNode target) {
            this.step = step;
            this.target = target;
        }

        /**
         * Get the AxisExpression associated with this arc
         * @return the AxisExpression
         */

        public AxisExpression getStep() {
            return step;
        }

        /**
         * Get the target node representing the destination of this arc
         * @return the target node
         */

        public PathMapNode getTarget() {
            return target;
        }
    }

    /**
     * A (mutable) set of nodes in the path map
     */

    public static class PathMapNodeSet extends HashSet {

        /**
         * Create an initially-empty set of path map nodes
         */

        public PathMapNodeSet() {}

        /**
         * Create a set of path map nodes that initially contains a single node
         * @param singleton the single node to act as the initial content
         */

        public PathMapNodeSet(PathMapNode singleton) {
            add(singleton);
        }

        /**
         * Create an arc from each node in this node set to a corresponding newly-created
         * target node
         * @param step the AxisExpression defining the transition
         * @return the set of new target nodes
         */

        public PathMapNodeSet createArc(AxisExpression step) {
            PathMapNodeSet targetSet = new PathMapNodeSet();
            for (Iterator it=iterator(); it.hasNext();) {
                PathMapNode node = (PathMapNode)it.next();
                targetSet.add(node.createArc(step));
            }
            return targetSet;
        }

        /**
         * Combine two node sets into one
         * @param nodes the set of nodes to be added to this set
         */

        public void addNodeSet(PathMapNodeSet nodes) {
            if (nodes != null) {
                for (Iterator it=nodes.iterator(); it.hasNext();) {
                    PathMapNode node = (PathMapNode)it.next();
                    add(node);
                }
            }
        }

        /**
         * Set the atomized property on all nodes in this nodeset
         */

        public void setAtomized() {
            for (Iterator it=iterator(); it.hasNext();) {
                PathMapNode node = (PathMapNode)it.next();
                node.setAtomized();
            }
        }

        /**
         * Indicate that all the descendants of the nodes in this nodeset are required
         */

        public void addDescendants() {
            for (Iterator it=iterator(); it.hasNext();) {
                PathMapNode node = (PathMapNode)it.next();
                AxisExpression down = new AxisExpression(Axis.DESCENDANT, AnyNodeTest.getInstance());
                node.createArc(down);
            }
        }

        /**
         * Indicate that all the nodes have unknown dependencies
         */

        public void setHasUnknownDependencies() {
            for (Iterator it=iterator(); it.hasNext();) {
                PathMapNode node = (PathMapNode)it.next();
                node.setHasUnknownDependencies();
            }
        }

    }

    /**
     * Create the PathMap for an expression
     * @param exp the expression whose PathMap is required
     */

    public PathMap(Expression exp) {
        PathMapNodeSet finalNodes = exp.addToPathMap(this, null);
        if (finalNodes != null) {
            for (Iterator iter = finalNodes.iterator(); iter.hasNext(); ) {
                PathMapNode node = (PathMapNode)iter.next();
                node.setReturnable(true);
            }
        }
    }

    /**
     * Make a new root node in the path map. However, if there is already a root for the same
     * expression, the existing root for that expression is returned.
     * @param exp the expression represented by this root node
     * @return the new root node
     */

    public PathMapRoot makeNewRoot(Expression exp) {
        for (int i=0; i<pathMapRoots.size(); i++) {
            PathMapRoot r = (PathMapRoot)pathMapRoots.get(i);
            if (exp.equals(r.getRootExpression())) {
                return r;
            }
        }
        PathMapRoot root = new PathMapRoot(exp);
        pathMapRoots.add(root);
        return root;
    }

    /**
     * Get all the root expressions from the path map
     * @return an array containing the root expressions
     */

    public PathMapRoot[] getPathMapRoots() {
        return (PathMapRoot[])pathMapRoots.toArray(new PathMapRoot[pathMapRoots.size()]);
    }

    /**
     * Register the path used when evaluating a given variable binding
     * @param binding the variable binding
     * @param nodeset the set of PathMap nodes reachable when evaluating that variable
     */

    public void registerPathForVariable(Binding binding, PathMapNodeSet nodeset) {
        pathsForVariables.put(binding, nodeset);
    }

    /**
     * Get the path used when evaluating a given variable binding
     * @param binding the variable binding
     * @return the set of PathMap nodes reachable when evaluating that variable
     */

    public PathMapNodeSet getPathForVariable(Binding binding) {
        return (PathMapNodeSet)pathsForVariables.get(binding);
    }

    /**
     * Get the path map root for the context document
     * @return the path map root for the context document if there is one, or null if none is found.
     * @throws IllegalStateException if there is more than one path map root for the context document
     */

    public PathMapRoot getContextRoot() {
        //System.err.println("BEFORE REDUCTION:");
        //map.diagnosticDump(System.err);
        PathMap.PathMapRoot[] roots = getPathMapRoots();
        PathMapRoot contextRoot = null;
        for (int r=0; r<roots.length; r++) {
            PathMap.PathMapRoot newRoot = reduceToDownwardsAxes(roots[r]);
            if (newRoot.getRootExpression() instanceof RootExpression) {
                if (contextRoot != null) {
                    throw new IllegalStateException("More than one context document root found in path map");
                } else {
                    contextRoot = newRoot;
                }
            }
        }
        //System.err.println("AFTER REDUCTION:");
        //map.diagnosticDump(System.err);
        return contextRoot;
    }

    /**
     * Get the path map root for a call on the doc() or document() function with a given literal argument
     * @param requiredUri the literal argument we are looking for
     * @return the path map root for the specified document if there is one, or null if none is found.
     * @throws IllegalStateException if there is more than one path map root for the specified document
     */

    public PathMapRoot getRootForDocument(String requiredUri) {
        //System.err.println("BEFORE REDUCTION:");
        //map.diagnosticDump(System.err);
        PathMap.PathMapRoot[] roots = getPathMapRoots();
        PathMapRoot requiredRoot = null;
        for (int r=0; r<roots.length; r++) {
            PathMap.PathMapRoot newRoot = reduceToDownwardsAxes(roots[r]);
            Expression exp = newRoot.getRootExpression();
            String baseUri = null;
            if (exp instanceof Doc) {
                baseUri = ((Doc)exp).getStaticBaseURI();
            } else if (exp instanceof Document) {
                baseUri = ((Document)exp).getStaticBaseURI();
            }
            Expression arg = ((SystemFunction)exp).getArguments()[0];
            String suppliedUri = null;
            if (arg instanceof Literal) {
                try {
                    String argValue = ((Literal)arg).getValue().getStringValue();
                    if (baseUri == null) {
                        if (new URI(argValue).isAbsolute()) {
                            suppliedUri = argValue;
                        } else {
                            suppliedUri = null;
                        }
                    } else {
                        suppliedUri = Configuration.getPlatform().makeAbsolute(argValue, baseUri).toString();
                    }
                } catch (URISyntaxException err) {
                    suppliedUri = null;
                } catch (XPathException err) {
                    suppliedUri = null;
                }
            }
            if (requiredUri.equals(suppliedUri)) {
                if (requiredRoot != null) {
                    throw new IllegalStateException("More than one document root found in path map for " + requiredUri);
                } else {
                    requiredRoot = newRoot;
                }
            }
        }
        //System.err.println("AFTER REDUCTION:");
        //map.diagnosticDump(System.err);
        return requiredRoot;
    }

    /**
     * Given a PathMapRoot, simplify the tree rooted at this node so that
     * it only contains downwards selections: specifically, so that the only axes
     * used are child, attribute, namespace, and descendant. If the root expression
     * is a ContextItemExpression (that is, the path can start at any node) then it is rebased
     * to start at a root node, which means in effect that a path such as a/b/c is treated
     * as //a/b/c.
     * @param root the root of the path to be simplified
     * @return the path map root after converting the tree to use downwards axes only
     */

    public PathMapRoot reduceToDownwardsAxes(PathMapRoot root) {
        // If the path is rooted at an arbitrary context node, we rebase it to be rooted at the
        // document root. This involves changing the root to a RootExpression, and changing the axis
        // for initial steps from child to descendant where necessary
        if (root.isDownwardsOnly) {
            return root;
        }
        PathMapRoot newRoot = root;
        if (root.getRootExpression() instanceof ContextItemExpression) {
            RootExpression slash = new RootExpression();
            slash.setContainer(root.getRootExpression().getContainer());
            //root.setRootExpression(slash);
            newRoot = makeNewRoot(slash);
            for (int i=root.arcs.size()-1; i>=0; i--) {
                PathMapArc arc = (PathMapArc)root.arcs.get(i);
                byte axis = arc.getStep().getAxis();
                switch (axis) {
                    case Axis.ATTRIBUTE:
                    case Axis.NAMESPACE: {
                        AxisExpression newStep = new AxisExpression(Axis.DESCENDANT, NodeKindTest.ELEMENT);
                        PathMapNode newTarget = new PathMapNode();
                        newTarget.arcs.add(arc);
                        newRoot.createArc(newStep, newTarget);
                        break;
                    }
                    default: {
                        AxisExpression newStep = new AxisExpression(
                                Axis.DESCENDANT_OR_SELF, arc.getStep().getNodeTest());
                        newRoot.createArc(newStep, arc.getTarget());
                        break;
                    }
                }
            }
            for (int i=0; i<pathMapRoots.size(); i++) {
                if (pathMapRoots.get(i) == root) {
                    pathMapRoots.remove(i); break;
                }
            }
        }
        // Now process the tree of paths recursively, rewriting all axes in terms of downwards
        // selections, if necessary as downward selections from the root
        Stack nodeStack = new Stack();
        nodeStack.push(newRoot);
        reduceToDownwardsAxes(newRoot, nodeStack);
        newRoot.isDownwardsOnly = true;
        return newRoot;
    }

    /**
     * Supporting method for {@link #reduceToDownwardsAxes(net.sf.saxon.expr.PathMap.PathMapRoot)}
     * @param root the root of the path being simplified
     * @param nodeStack the sequence of nodes by which the current node in the path map was reached.
     * The node at the bottom of the stack is the root.
     */

    private void reduceToDownwardsAxes(PathMapRoot root, Stack nodeStack) {
        //PathMapArc lastArc = (PathMapArc)arcStack.peek();
        //byte lastAxis = lastArc.getStep().getAxis();
        PathMapNode node = (PathMapNode)nodeStack.peek();
        if (node.hasUnknownDependencies()) {
            root.setHasUnknownDependencies();
        }

        for (int i=0; i<node.arcs.size(); i++) {
            nodeStack.push(((PathMapArc)node.arcs.get(i)).getTarget());
            reduceToDownwardsAxes(root, nodeStack);
            nodeStack.pop();
        }        

        for (int i=node.arcs.size()-1; i>=0; i--) {
            PathMapArc thisArc = (PathMapArc)node.arcs.get(i);
            AxisExpression axisStep = thisArc.getStep();
            PathMapNode grandParent =
                        (nodeStack.size() < 2 ? null : (PathMapNode)nodeStack.get(nodeStack.size()-2));
            byte lastAxis = -1;
            if (grandParent != null) {
                for (Iterator iter = grandParent.arcs.iterator(); iter.hasNext(); ) {
                    PathMapArc arc = ((PathMapArc)iter.next());
                    if (arc.getTarget() == node) {
                        lastAxis = arc.getStep().getAxis();
                    }
                }
            }
            switch (axisStep.getAxis()) {

                case Axis.ANCESTOR_OR_SELF:
                case Axis.DESCENDANT_OR_SELF:
                    if (axisStep.getNodeTest() == NodeKindTest.DOCUMENT) {
                        // This is typically an absolute path expression appearing within a predicate
                        node.arcs.remove(i);
                        for (Iterator iter = thisArc.getTarget().arcs.iterator(); iter.hasNext(); ) {
                            root.arcs.add(iter.next());
                        }
                        break;
                    } else {
                        // fall through
                    }

                case Axis.ANCESTOR:
                case Axis.FOLLOWING:
                case Axis.PRECEDING: {
                    // replace the axis by a downwards axis from the root
                    if (axisStep.getAxis() != Axis.DESCENDANT_OR_SELF) {
                        AxisExpression newStep = new AxisExpression(Axis.DESCENDANT_OR_SELF, axisStep.getNodeTest());
                        newStep.setContainer(axisStep.getContainer());
                        root.createArc(newStep, thisArc.getTarget());
                        node.arcs.remove(i);
                    }
                    break;
                }

                case Axis.ATTRIBUTE:
                case Axis.CHILD:
                case Axis.DESCENDANT:
                case Axis.NAMESPACE:
                    // no action
                    break;

                case Axis.FOLLOWING_SIBLING:
                case Axis.PRECEDING_SIBLING: {
                    if (grandParent != null) {
                        AxisExpression newStep = new AxisExpression(lastAxis, axisStep.getNodeTest());
                        newStep.setContainer(axisStep.getContainer());
                        grandParent.createArc(newStep, thisArc.getTarget());
                        node.arcs.remove(i);
                        break;
                    } else {
                        AxisExpression newStep = new AxisExpression(Axis.CHILD, axisStep.getNodeTest());
                        newStep.setContainer(axisStep.getContainer());
                        root.createArc(newStep, thisArc.getTarget());
                        node.arcs.remove(i);
                        break;
                    }
                }
                case Axis.PARENT: {

                    if (lastAxis == Axis.CHILD || lastAxis == Axis.ATTRIBUTE || lastAxis == Axis.NAMESPACE) {
                        // ignore the parent step - it leads to somewhere we have already been.
                        // But it might become a returned node
                        if (node.isReturnable()) {
                            grandParent.setReturnable(true);
                        }
                        // any paths after the parent step need to be attached to the grandparent

                        PathMapNode target = thisArc.getTarget();
                        for (int a=0; a<target.arcs.size(); a++) {
                            grandParent.arcs.add(target.arcs.get(a));
                        }
                        node.arcs.remove(i);
                    } else if (lastAxis == Axis.DESCENDANT) {
                        AxisExpression newStep = new AxisExpression(Axis.DESCENDANT_OR_SELF, axisStep.getNodeTest());
                        newStep.setContainer(axisStep.getContainer());
                        if (thisArc.getTarget().arcs.isEmpty()) {
                            grandParent.createArc(newStep);
                        } else {
                            grandParent.createArc(newStep, thisArc.getTarget());
                        }
                        node.arcs.remove(i);
                    } else {
                        // don't try to be precise about a/b/../../c
                        AxisExpression newStep = new AxisExpression(Axis.DESCENDANT_OR_SELF, axisStep.getNodeTest());
                        newStep.setContainer(axisStep.getContainer());
                        if (thisArc.getTarget().arcs.isEmpty()) {
                            root.createArc(newStep);
                        } else {
                            root.createArc(newStep, thisArc.getTarget());
                        }
                        node.arcs.remove(i);
                    } 
                    break;
                }
                case Axis.SELF: {
                    // This step can't take us anywhere we haven't been, so delete it
                    node.arcs.remove(i);
                    break;
                }
            }
        }

    }

    /**
     * Display a printed representation of the path map
     * @param out the output stream to which the output will be written
     */

    public void diagnosticDump(PrintStream out) {
        for (int i=0; i<pathMapRoots.size(); i++) {
            out.println("\nROOT EXPRESSION " + i);
            PathMapRoot mapRoot = (PathMapRoot)pathMapRoots.get(i);
            if (mapRoot.hasUnknownDependencies()) {
                out.println("  -- has unknown dependencies --");
            }
            Expression exp = mapRoot.rootExpression;
            exp.explain(out);
            out.println("\nTREE FOR EXPRESSION " + i);
            showArcs(out, mapRoot, 2);
        }
    }

    /**
     * Internal helper method called by diagnosticDump, to show the arcs emanating from a node.
     * Each arc is shown as a representation of the axis step, followed optionally by "@" if the
     * node reached by the arc is atomized, followed optionally by "#" if the
     * node reached by the arc is a final returnable node.
     * @param out the output stream
     * @param node the node in the path map whose arcs are to be displayed
     * @param indent the indentation level in the output
     */

    private void showArcs(PrintStream out, PathMapNode node, int indent) {
        String pad = "                                           ".substring(0, indent);
        List arcs = node.arcs;
        for (int i=0; i<arcs.size(); i++) {
            PathMapArc arc = ((PathMapArc)arcs.get(i));
            out.println(pad + arc.step +
                    (arc.target.isAtomized() ? " @" : "") +
                    (arc.target.isReturnable() ? " #" : "") +
                    (arc.target.hasUnknownDependencies() ? " ...??" : ""));
            showArcs(out, arc.target, indent+2);
        }
    }

    /**
     * Main method for testing
     * @param args Takes one argument, the XPath expression to be analyzed
     * @throws Exception
     */

//    public static void main(String[] args) throws Exception {
//        Configuration config = new Configuration();
//        Expression exp;
//        if (args[0].equals("xpath")) {
//            XPathEvaluator xpath = new XPathEvaluator(config);
//            XPathExpression xpexp = xpath.createExpression(args[1]);
//            exp = xpexp.getInternalExpression();
//        } else if (args[0].equals("xquery")) {
//            StaticQueryContext sqc = new StaticQueryContext(config);
//            sqc.setBaseURI(new File(args[1]).toURI().toString());
//            XQueryExpression xqe = sqc.compileQuery(new FileReader(args[1]));
//            exp = xqe.getExpression();
//        } else {
//            throw new IllegalArgumentException("first argument must be xpath or xquery");
//        }
//        exp.explain(System.err);
//        PathMap initialPath = new PathMap(exp);
//        initialPath.diagnosticDump(System.err);
//
//        PathMapRoot[] roots = initialPath.getPathMapRoots();
//        for (int i=0; i<roots.length; i++) {
//            initialPath.reduceToDownwardsAxes(roots[i]);
//        }
//        System.err.println("AFTER REDUCTION:");
//        initialPath.diagnosticDump(System.err);
//    }

}

//
// The contents of this file are subject to the Mozilla Public License Version 1.0 (the "License");
// you may not use this file except in compliance with the License. You may obtain a copy of the
// License at http://www.mozilla.org/MPL/
//
// Software distributed under the License is distributed on an "AS IS" basis,
// WITHOUT WARRANTY OF ANY KIND, either express or implied.
// See the License for the specific language governing rights and limitations under the License.
//
// The Original Code is: all this file
//
// The Initial Developer of the Original Code is Michael H. Kay.
//
// Contributor(s):
//

