package datawave.query.jexl.visitors;

import com.google.common.collect.Lists;
import datawave.query.jexl.JexlASTHelper;
import datawave.query.jexl.JexlNodeFactory;
import org.apache.commons.jexl2.parser.ASTEQNode;
import org.apache.commons.jexl2.parser.ASTJexlScript;
import org.apache.commons.jexl2.parser.ASTReference;
import org.apache.commons.jexl2.parser.ASTReferenceExpression;
import org.apache.commons.jexl2.parser.JexlNode;
import org.apache.commons.jexl2.parser.JexlNodes;
import org.apache.commons.jexl2.parser.ParseException;
import org.apache.commons.jexl2.parser.ParserTreeConstants;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RemoveExtraParensVisitorTest {
    
    @Test
    public void testSimpleCase() throws ParseException {
        String query = "((FOO == 'bar'))";
        String expected = "(FOO == 'bar')";
        test(query, expected);
    }
    
    @Test
    public void testLotsOfWraps() throws ParseException {
        String query = "((((((((((((((((((FOO == 'bar'))))))))))))))))))";
        String expected = "(FOO == 'bar')";
        test(query, expected);
    }
    
    @Test
    public void testLessThanSimpleCase() throws ParseException {
        String query = "(((((FOO == 'bar')) || (FOO2 == 'bar2'))))";
        String expected = "((FOO == 'bar') || (FOO2 == 'bar2'))";
        test(query, expected);
    }
    
    @Test
    public void testExtraReferences() {
        ASTReference a = new ASTReference(ParserTreeConstants.JJTREFERENCE);
        ASTReference b = new ASTReference(ParserTreeConstants.JJTREFERENCE);
        ASTReference c = new ASTReference(ParserTreeConstants.JJTREFERENCE);
        ASTEQNode eq = (ASTEQNode) JexlNodeFactory.buildEQNode("FIELD", "value");
        
        JexlNodes.children(a, b);
        JexlNodes.children(b, c);
        JexlNodes.children(c, eq);
        
        // Build expected tree from copy
        ASTReference expected = new ASTReference(ParserTreeConstants.JJTREFERENCE);
        ASTEQNode child = (ASTEQNode) JexlNodeFactory.buildEQNode("FIELD", "value");
        JexlNodes.children(expected, child);
        
        JexlNode removed = RemoveExtraParensVisitor.remove(a);
        
        assertTrue(TreeEqualityVisitor.isEqual(expected, removed));
    }
    
    @Test
    public void testExtraReferenceExpressions() {
        ASTReferenceExpression a = new ASTReferenceExpression(ParserTreeConstants.JJTREFERENCEEXPRESSION);
        ASTReferenceExpression b = new ASTReferenceExpression(ParserTreeConstants.JJTREFERENCEEXPRESSION);
        ASTReferenceExpression c = new ASTReferenceExpression(ParserTreeConstants.JJTREFERENCEEXPRESSION);
        ASTEQNode eq = (ASTEQNode) JexlNodeFactory.buildEQNode("FIELD", "value");
        
        JexlNodes.children(a, b);
        JexlNodes.children(b, c);
        JexlNodes.children(c, eq);
        
        // Build expected tree from copy
        ASTReferenceExpression expected = new ASTReferenceExpression(ParserTreeConstants.JJTREFERENCEEXPRESSION);
        ASTEQNode child = (ASTEQNode) JexlNodeFactory.buildEQNode("FIELD", "value");
        JexlNodes.children(expected, child);
        
        JexlNode removed = RemoveExtraParensVisitor.remove(a);
        
        assertTrue(TreeEqualityVisitor.isEqual(expected, removed));
        
        assertLineage(removed);
    }
    
    @Test
    public void testExtraReferencesAndExtraReferenceExpressions() {
        ASTReference a = new ASTReference(ParserTreeConstants.JJTREFERENCE);
        ASTReference b = new ASTReference(ParserTreeConstants.JJTREFERENCE);
        ASTReferenceExpression c = new ASTReferenceExpression(ParserTreeConstants.JJTREFERENCEEXPRESSION);
        ASTReferenceExpression d = new ASTReferenceExpression(ParserTreeConstants.JJTREFERENCEEXPRESSION);
        ASTEQNode eq = (ASTEQNode) JexlNodeFactory.buildEQNode("FIELD", "value");
        
        JexlNodes.children(a, b);
        JexlNodes.children(b, c);
        JexlNodes.children(c, d);
        JexlNodes.children(d, eq);
        
        // Build expected tree from copy
        ASTReference expected = new ASTReference(ParserTreeConstants.JJTREFERENCE);
        ASTReferenceExpression child = new ASTReferenceExpression(ParserTreeConstants.JJTREFERENCEEXPRESSION);
        ASTEQNode grandchild = (ASTEQNode) JexlNodeFactory.buildEQNode("FIELD", "value");
        JexlNodes.children(expected, child);
        JexlNodes.children(child, grandchild);
        
        JexlNode removed = RemoveExtraParensVisitor.remove(a);
        
        assertTrue(TreeEqualityVisitor.isEqual(expected, removed));
        
        assertLineage(removed);
    }
    
    @Test
    public void testOddRefExprOrder() throws ParseException {
        JexlNode leftEq = JexlNodeFactory.buildEQNode("F", "v");
        JexlNode leftNode = JexlNodes.wrap(JexlNodes.makeRef(JexlNodes.wrap(leftEq)));
        
        JexlNode rightEq = JexlNodeFactory.buildEQNode("F2", "v2");
        JexlNode rightNode = JexlNodes.makeRef(JexlNodes.wrap(rightEq));
        
        JexlNode union = JexlNodeFactory.createOrNode(Arrays.asList(leftNode, rightNode));
        
        String expectedQuery = "((F == 'v') || (F2 == 'v2'))";
        test(JexlStringBuildingVisitor.buildQueryWithoutParse(union), expectedQuery);
    }
    
    @Test
    public void testDelayedRegex() throws ParseException {
        String query = "((_Delayed_ = true) && (FOO =~ '.*regex.*'))";
        test(query, query);
    }
    
    private void test(String query, String expected) throws ParseException {
        ASTJexlScript script = JexlASTHelper.parseAndFlattenJexlQuery(query);
        ASTJexlScript visited = (ASTJexlScript) RemoveExtraParensVisitor.remove(script);
        
        String visitedString = JexlStringBuildingVisitor.buildQueryWithoutParse(visited);
        assertEquals(expected, visitedString);
        
        assertLineage(visited);
    }
    
    private void assertLineage(JexlNode node) {
        assertTrue(JexlASTHelper.validateLineage(node, true));
    }
}
