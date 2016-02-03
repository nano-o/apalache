package at.forsyte.apalache.tla.parser

import String.format

import at.forsyte.apalache.tla.ir._

import scala.collection.immutable.TreeSet
import scala.xml.Node

/**
 * A parser of TLA+ code from XML generated by XMLExporter.
 *
 * Note that the same instance of XmlImporter should not be used in different threads.
 *
 * @see tla2sany.XMLExporter
 *
 * @todo do a preliminary structure check with XML schema?
 *
 * @author konnov
 */
class XmlImporter {
  var contextToParse: Map[Int, xml.Node] = Map()
  var context: Map[Int, TlaNode] = Map()
  var maxUid = 0

  /**
   * Parse an XML tree created by SANY
   * @param node root node (tagged with modules)
   * @return an internal representation of the TLA spec
   */
  def parse(node: xml.Node): Spec = {
    maxUid = 0
    if (node.label != "modules")
    throw new XmlImporterException(String.format("Expected <modules>...</modules>, found <%s></%s>",
      node.label, node.label))

    contextToParse = collectContextNodes(node \ "context" \ "entry")
    context = Map()
    new Spec((node \ "ModuleNode").map(parseModule).toList)
  }

  private def nextUid() = {
    val uid = maxUid
    maxUid = maxUid + 1
    uid
  }

  // updates maxUid and returns a map from uid to the corresponding node
  private def collectContextNodes(nodes: xml.NodeSeq): Map[Int, xml.Node] = {
    def getUid(node: xml.Node): Int =
      (node \ "UID").text.toInt

    // pick any Elem (not Text!) different from UID
    def getSub(node: xml.Node): xml.Node = {
      val sub = (node \ "_").filter(_.label != "UID")
      if (sub.length == 1)
        sub.head
      else
        throw new XmlImporterException(format("Unexpected entry structure: %s (sub = %s)",
          node.toString(), sub.toString()))
    }

    maxUid = 1 + nodes.map(getUid).foldLeft(0)(Math.max)
    nodes.foldLeft (Map[Int, xml.Node]()) ((m, n) => m + (getUid(n) -> getSub(n)))
  }

  private def parseModule(node: xml.Node): Module = {
    val uniqueName = (node \ "uniquename").text
    val origin = parseOrigin(nextUid(), 0, (node \ "location").head)
    val vars = (node \ "variables" \ "OpDeclNodeRef" \ "UID").map(n => parseOpDecl(Kind.Var, n.text.toInt)).toList

    //<definitions>
    //  <UserDefinedOpKindRef>
    val operators = (node \ "definitions" \ "UserDefinedOpKindRef" \ "UID").map(n => parseOpDef (n.text.toInt)).toList

    new Module(origin, uniqueName, constants = List(), variables = vars,
      operators = List(), assumptions = List(), theorems = List())
  }

  private def parseOpDecl(kind: Kind.Value, uid: Int): UserOpDecl = {
    context.get(uid) match {
      case Some(node) =>
        node.asInstanceOf[UserOpDecl]

      case _ =>
        val node = contextToParse(uid)
        assert(node.label == "OpDeclNode")
        val uniqueName = (node \ "uniquename").text
        val level = (node \ "level").text.toInt
        val origin = parseOrigin(uid, level, (node \ "location").head)
        val arity = (node \ "arity").text.toInt
        val kindId = (node \ "kind").text.toInt
        if (Kind(kindId) != kind)
          throw new XmlImporterException(format("Expected kind %s, found %s", Kind(kindId), kind))

        val decl = UserOpDecl(uniqueName = uniqueName, origin = origin, arity = arity, kind = kind)
        context += uid -> decl
        decl
    }
  }

  private def parseOpDef(uid: Int): UserOpDef = {
    context.get(uid) match {
      case Some(node) =>
        node.asInstanceOf[UserOpDef]

      case _ =>
        val node = contextToParse(uid)
        assert(node.label == "UserDefinedOpKind")
        val uniqueName = (node \ "uniquename").text
        val level = (node \ "level").text.toInt
        val origin = parseOrigin(uid, level, (node \ "location").head)
        val arity = (node \ "arity").text.toInt
        val body = parseChildExprNode((node \ "body").head)
        val params = List() // todo: parse params
        assert(params.size == arity)
        val oper = UserOpDef(uniqueName = uniqueName, params, body, origin)
        context += uid -> oper
        oper
    }
  }

  private def parseBuiltinOp(uid: Int): BuiltinOp = {
    context.get(uid) match {
      case Some(node) =>
        node.asInstanceOf[BuiltinOp]

      case _ =>
        val node = contextToParse(uid)
        assert(node.label == "BuiltInKind")
        val uniqueName = (node \ "uniquename").text
        val level = (node \ "level").text.toInt
        val origin = parseOrigin(uid, level, (node \ "location").head)
        val arity = (node \ "arity").text.toInt
        val params = (node \ "params" \ "leibnizparam" \ "FormalParamNodeRef" \ "UID")
          .map(n => parseFormalParam(n.text.toInt)).toList
        assert(params.size == arity)
        val oper = BuiltinOp(uniqueName = uniqueName, params, origin)
        context += uid -> oper
        oper
    }
  }

  private def parseFormalParam(uid: Int) = {
    context.get(uid) match {
      case Some(node) =>
        node.asInstanceOf[FormalParam]

      case _ =>
        val node = contextToParse(uid)
        assert(node.label == "FormalParamNode")
        val uniqueName = (node \ "uniquename").text
        val arity = (node \ "arity").text.toInt
        val param = FormalParam(uniqueName = uniqueName, arity = arity)
        context += uid -> param
        param
    }

  }

  private def parseChildExprNode(parent: xml.Node): TlaNode = {
    // make it final?
    val options = TreeSet("AtNode",
      "DecimalNode", "LabelNode", "LetInNode", "NumeralNode", "OpApplNode", "StringNode", "SubstInNode")
    val exprNode = parent.child.find(n => options.contains(n.label))
    exprNode match {
      case Some(n) =>
        parseExprNode(n)

      case None =>
        val msg = format("Expected one of the expression nodes at XML node@%s: %s", (parent \ "UID").text, options)
        throw new XmlImporterException(msg)
    }
  }

  private def parseExprNode(node: xml.Node): TlaNode = {
    node.label match {
      case "OpApplNode" =>
        parseOpApplNode(node)

      case _ => // todo: parse the other types of nodes
        throw new XmlImporterException("Unexpected expression type: " + node.label)
    }
  }

  private def parseOpApplNode(node: xml.Node): OpApply = {
    val level = (node \ "level").text.toInt
    val origin = parseOrigin(nextUid(), level, (node \ "location").head)
    val operNodes = node \ "operator"
    val oper =
      if (operNodes.size == 1)
        parseChildOperRef(operNodes.head)
      else throw new XmlImporterException("Incorrect OpApplNode.operator: " + node)
    val operandsNodes = node \ "operands"
    val operands = operandsNodes.head.child.map(parseExprNode).toList
    // todo: parse boundSymbols
    OpApply(oper = oper, args = operands, origin = origin)
  }

  private def parseChildOperRef(parent: xml.Node) = {
    // make it final?
    val options = TreeSet("FormalParamNodeRef", "ModuleNodeRef", "OpDeclNodeRef", "TheoremNodeRef",
      "AssumeNodeRef", "ModuleInstanceKindRef", "UserDefinedOpKindRef", "BuiltInKindRef")
    val exprNode = parent.child.find(n => options.contains(n.label))
    exprNode match {
      case Some(ref) =>
        parseOperRef(ref)

      case None =>
        val msg = format("Expected one of the expression nodes (%s) at XML node %s", options, parent)
        throw new XmlImporterException(msg)
    }
  }

  private def parseOperRef(node: xml.Node) = {
    node.label match {
      case "BuiltInKindRef" =>
        parseBuiltinOp((node \ "UID").text.toInt)

      case "UserDefinedOpKindRef" =>
        parseOpDef((node \ "UID").text.toInt)

      case _ => // todo: parse the other kinds
        throw new XmlImporterException("Unexpected expression type: " + node.label)
    }
  }

  private def parseOrigin(uid: Int, level: Int, node: xml.Node): Origin = {
    val filename = (node \ "filename").text
    val startCol = (node \ "column" \ "begin").text.toInt
    val endCol = (node \ "column" \ "end").text.toInt
    val startLine = (node \ "line" \ "begin").text.toInt
    val endLine = (node \ "line" \ "end").text.toInt
    new Origin(uid, level, filename, LocRange(Loc(startLine, startCol), Loc(endLine, endCol)))
  }
}
