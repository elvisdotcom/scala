/* NSC -- new Scala compiler
 * Copyright 2005-2007 LAMP/EPFL
 * @author  Martin Odersky
 */
// $Id$

package scala.tools.nsc.typechecker
import scala.tools.nsc.util.{Position, NoPosition}
import scala.collection.mutable.ListBuffer
import symtab.Flags._

/** This trait ...
 *
 *  @author Martin Odersky
 *  @version 1.0
 */
trait Infer {
  self: Analyzer =>
  import global._
  import definitions._
  import posAssigner.atPos

  // statistics
  var normM = 0
  var normP = 0
  var normO = 0

/* -- Type parameter inference utility functions --------------------------- */

  def assertNonCyclic(tvar: TypeVar) =
    assert(tvar.constr.inst != tvar, tvar.origin)

  def isVarArgs(formals: List[Type]) =
    !formals.isEmpty && (formals.last.typeSymbol == RepeatedParamClass)

  /** The formal parameter types corresponding to <code>formals</code>.
   *  If <code>formals</code> has a repeated last parameter, a list of
   *  (nargs - params.length + 1) copies of its type is returned.
   *
   *  @param formals ...
   *  @param nargs ...
   */
  def formalTypes(formals: List[Type], nargs: int): List[Type] = {
    val formals1 = formals map {
      case TypeRef(_, sym, List(arg)) if (sym == ByNameParamClass) => arg
      case formal => formal
    }
    if (isVarArgs(formals1)) {
      val ft = formals1.last.normalize.typeArgs.head
      formals1.init ::: (for (i <- List.range(formals1.length - 1, nargs)) yield ft)
    } else formals1
  }

  def actualTypes(actuals: List[Type], nformals: int): List[Type] =
    if (nformals == 1 && actuals.length != 1)
      List(if (actuals.length == 0) UnitClass.tpe else tupleType(actuals))
    else actuals

  def actualArgs(pos: Position, actuals: List[Tree], nformals: int): List[Tree] =
    if (nformals == 1 && actuals.length != 1) List(atPos(pos)(gen.mkTuple(actuals))) else actuals

  /** A fresh type varable with given type parameter as origin.
   *
   *  @param tparam ...
   *  @return       ...
   */
  def freshVar(tparam: Symbol): TypeVar =
    new TypeVar(tparam.tpe, new TypeConstraint)  //@M TODO: might be affected by change to tpe in Symbol

  //todo: remove comments around following privates; right now they cause an IllegalAccess
  // error when built with scalac

  /*private*/ class NoInstance(msg: String) extends RuntimeException(msg)

  /*private*/ class DeferredNoInstance(getmsg: () => String) extends NoInstance("") {
    override def getMessage(): String = getmsg()
  }

  /*private*/ object instantiateMap extends TypeMap {
    def apply(t: Type): Type = instantiate(t)
  }

  /** map every TypeVar to its constraint.inst field.
   *  throw a NoInstance exception if a NoType or WildcardType is encountered.
   *
   *  @param  tp ...
   *  @return    ...
   *  @throws    NoInstance
   */
  def instantiate(tp: Type): Type = tp match {
    case WildcardType | NoType =>
      throw new NoInstance("undetermined type")
    case TypeVar(origin, constr) =>
      if (constr.inst != NoType) instantiate(constr.inst)
      else throw new DeferredNoInstance(() =>
        "no unique instantiation of type variable " + origin + " could be found")
    case _ =>
      instantiateMap.mapOver(tp)
  }

  /** Is type fully defined, i.e. no embedded anytypes or wildcards in it?
   *
   *  @param tp ...
   *  @return   ...
   */
  def isFullyDefined(tp: Type): boolean = tp match {
    case WildcardType | NoType =>
      false
    case NoPrefix | ThisType(_) | ConstantType(_) =>
      true
    case TypeRef(pre, sym, args) =>
      isFullyDefined(pre) && (args.isEmpty || (args forall isFullyDefined))
    case SingleType(pre, sym) =>
      isFullyDefined(pre)
    case RefinedType(ts, decls) =>
      ts forall isFullyDefined
    case TypeVar(origin, constr) if (constr.inst == NoType) =>
      false
    case _ =>
      try {
        instantiate(tp); true
      } catch {
        case ex: NoInstance => false
      }
  }

  /** Solve constraint collected in types <code>tvars</code>.
   *
   *  @param tvars      All type variables to be instantiated.
   *  @param tparams    The type parameters corresponding to <code>tvars</code>
   *  @param variances  The variances of type parameters; need to reverse
   *                    solution direction for all contravariant variables.
   *  @param upper      When <code>true</code> search for max solution else min.
   *  @throws NoInstance
   */
  private def solvedTypes(tvars: List[TypeVar], tparams: List[Symbol],
                          variances: List[int], upper: boolean): List[Type] = {
    solve(tvars, tparams, variances, upper)
    for (val tvar <- tvars) assert(tvar.constr.inst != tvar, tvar.origin)
    tvars map instantiate
  }

  def skipImplicit(tp: Type) =
    if (tp.isInstanceOf[ImplicitMethodType]) tp.resultType else tp

  /** Automatically perform the following conversions on expression types:
   *  A method type becomes the corresponding function type.
   *  A nullary method type becomes its result type.
   *  Implicit parameters are skipped.
   *
   *  @param tp ...
   *  @return   ...
   */
  def normalize(tp: Type): Type = skipImplicit(tp) match {
    case MethodType(formals, restpe) if (!restpe.isDependent) =>
      if (util.Statistics.enabled) normM = normM + 1
      functionType(formals, normalize(restpe))
    case PolyType(List(), restpe) =>
      if (util.Statistics.enabled) normP = normP + 1
      normalize(restpe)
    case tp1 =>
      if (util.Statistics.enabled) normO = normO + 1
      tp1 // @MAT aliases already handled by subtyping
  }

  private val stdErrorClass = RootClass.newErrorClass(nme.ERROR.toTypeName)
  private val stdErrorValue = stdErrorClass.newErrorValue(nme.ERROR)

  /** The context-dependent inferencer part */
  class Inferencer(context: Context) {

    /* -- Error Messages --------------------------------------------------- */

    def setError[T <: Tree](tree: T): T = {
      if (tree.hasSymbol)
        if (context.reportGeneralErrors) {
          val name = newTermName("<error: " + tree.symbol + ">")
          tree.setSymbol(
            if (tree.isType) context.owner.newErrorClass(name.toTypeName)
            else context.owner.newErrorValue(name))
        } else {
          tree.setSymbol(if (tree.isType) stdErrorClass else stdErrorValue)
        }
      tree.setType(ErrorType)
    }

    def decode(name: Name): String =
      (if (name.isTypeName) "type " else "value ") + name.decode

    def treeSymTypeMsg(tree: Tree): String =
      if (tree.symbol eq null)
        "expression of type " + tree.tpe
      else if (tree.symbol.hasFlag(OVERLOADED))
        "overloaded method " + tree.symbol + " with alternatives " + tree.tpe
      else
        tree.symbol.toString() +
        (if (tree.tpe.paramSectionCount > 0) ": " else " of type ") +
        tree.tpe +
        (if (tree.symbol.name == nme.apply) tree.symbol.locationString else "")

    def applyErrorMsg(tree: Tree, msg: String, argtpes: List[Type], pt: Type) = (
      treeSymTypeMsg(tree) + msg + argtpes.mkString("(", ",", ")") +
       (if (pt == WildcardType) "" else " with expected result type " + pt)
    )

    def foundReqMsg(found: Type, req: Type): String =
      withDisambiguation(found, req) {
        ";\n found   : " + found.toLongString + "\n required: " + req
      }

    def typeErrorMsg(found: Type, req: Type) =
      "type mismatch" + foundReqMsg(found, req) +
      (if ((found.resultApprox ne found) && isWeaklyCompatible(found.resultApprox, req))
        "\n possible cause: missing arguments for method or constructor"
       else "")

    def error(pos: Position, msg: String): unit =
      context.error(pos, msg)

    def errorTree(tree: Tree, msg: String): Tree = {
      if (!tree.isErroneous) error(tree.pos, msg)
      setError(tree)
    }

    def typeError(pos: Position, found: Type, req: Type) {
      if (!found.isErroneous && !req.isErroneous) {
        error(pos, typeErrorMsg(found, req))
        if (settings.explaintypes.value) explainTypes(found, req)
      }
    }

    def typeErrorTree(tree: Tree, found: Type, req: Type): Tree = {
      typeError(tree.pos, found, req)
      setError(tree)
    }

    def explainTypes(tp1: Type, tp2: Type) =
      withDisambiguation(tp1, tp2) { global.explainTypes(tp1, tp2) }

    /** If types `tp1' `tp2' contain different type variables with same name
     *  differentiate the names by including owner information
     */
    private def withDisambiguation[T](tp1: Type, tp2: Type)(op: => T): T = {

      def explainName(sym: Symbol) = {
        if (!sym.name.toString.endsWith(")")) {
          sym.name = newTypeName(sym.name.toString+"(in "+sym.owner+")")
        }
      }

      val patches = new ListBuffer[(Symbol, Symbol, Name)]
      for {
        t1 @ TypeRef(_, sym1, _) <- tp1
        t2 @ TypeRef(_, sym2, _) <- tp2
        if sym1 != sym2 && t1.toString == t2.toString
      } {
        val name = sym1.name
        explainName(sym1)
        explainName(sym2)
        if (sym1.owner == sym2.owner) sym2.name = newTypeName("(some other)"+sym2.name)
        patches += (sym1, sym2, name)
      }

      val result = op

      for ((sym1, sym2, name) <- patches) {
        sym1.name = name
        sym2.name = name
      }

      result
    }

    /* -- Tests & Checks---------------------------------------------------- */

    /** Check that <code>sym</code> is defined and accessible as a member of
     *  tree <code>site</code> with type <code>pre</code> in current context.
     *
     *  @param tree ...
     *  @param sym  ...
     *  @param pre  ...
     *  @param site ...
     *  @return     ...
     */
    def checkAccessible(tree: Tree, sym: Symbol, pre: Type, site: Tree): Tree =
      if (sym.isError) {
        tree setSymbol sym setType ErrorType
      } else {
        def accessError(explanation: String): Tree =
          errorTree(tree, underlying(sym).toString() + " cannot be accessed in " +
                    (if (sym.isClassConstructor) context.enclClass.owner else pre.widen) +
                    explanation)

        if (context.unit != null)
          context.unit.depends += sym.toplevelClass

        val sym1 = sym filter (alt => context.isAccessible(alt, pre, site.isInstanceOf[Super]))
        if (sym1 == NoSymbol) {
          if (settings.debug.value) {
            Console.println(context)
            Console.println(tree)
            Console.println("" + pre + " " + sym.owner + " " + context.owner + " " + context.outer.enclClass.owner + " " + sym.owner.thisType + (pre =:= sym.owner.thisType))
          }
          accessError("")
        } else {
          //Console.println("check acc " + sym1 + ":" + sym1.tpe + " from " + pre);//DEBUG
          var owntype = try{
            pre.memberType(sym1)
          } catch {
            case ex: MalformedType =>
              if (settings.debug.value) ex.printStackTrace
              val sym2 = underlying(sym1)
              val itype = withoutMalformedChecks(pre.memberType(sym2))
              accessError("\n because its instance type "+itype+
                          (if ("malformed type: "+itype.toString==ex.msg) " is malformed"
                           else " contains a "+ex.msg))
              ErrorType
          }
          if (pre.isInstanceOf[SuperType])
            owntype = owntype.substSuper(pre, site.symbol.thisType)
          tree setSymbol sym1 setType owntype
        }
      }

    def isPlausiblyCompatible(tp: Type, pt: Type): boolean = tp match {
      case PolyType(_, restpe) =>
        isPlausiblyCompatible(restpe, pt)
      case MethodType(formals, _) =>
        pt.normalize match {
          case TypeRef(pre, sym, args) =>
            !sym.isClass || {
              val l = args.length - 1
              l == formals.length &&
              sym == FunctionClass(l) &&
              List.forall2(args, formals) (isPlausiblySubType) &&
              isPlausiblySubType(tp.resultApprox, args.last)
            }
          case _ =>
            true
        }
      case _ =>
        true
    }

    def isPlausiblySubType(tp1: Type, tp2: Type): boolean = tp1.normalize match {
      case TypeRef(_, sym1, _) =>
        !sym1.isClass || {
          tp2.normalize match {
            case TypeRef(_, sym2, _) => !sym2.isClass || (sym1 isSubClass sym2)
            case _ => true
          }
        }
      case _ =>
        true
    }

    def isCompatible(tp: Type, pt: Type): boolean = {
      val tp1 = normalize(tp)
      (tp1 <:< pt) || isCoercible(tp, pt)
    }

    def isWeaklyCompatible(tp: Type, pt: Type): boolean =
      pt.typeSymbol == UnitClass || isCompatible(tp, pt)

    def isCoercible(tp: Type, pt: Type): boolean = false

    def isCompatible(tps: List[Type], pts: List[Type]): boolean =
      List.map2(tps, pts)((tp, pt) => isCompatible(tp, pt)) forall (x => x)

    /* -- Type instantiation------------------------------------------------ */

    /** Return inferred type arguments of polymorphic expression, given
     *  its type parameters and result type and a prototype <code>pt</code>.
     *  If no minimal type variables exist that make the
     *  instantiated type a subtype of <code>pt</code>, return null.
     *
     *  @param tparams ...
     *  @param restpe  ...
     *  @param pt      ...
     *  @return        ...
     */
    private def exprTypeArgs(tparams: List[Symbol], restpe: Type, pt: Type): List[Type] = {
      val tvars = tparams map freshVar
      if (isCompatible(restpe.instantiateTypeParams(tparams, tvars), pt)) {
        try {
          solvedTypes(tvars, tparams, tparams map varianceInType(restpe), false)
        } catch {
          case ex: NoInstance => null
        }
      } else null
    }

    /** Return inferred proto-type arguments of function, given
    *  its type and value parameters and result type, and a
    *  prototype <code>pt</code> for the function result.
    *  Type arguments need to be either determined precisely by
    *  the prototype, or they are maximized, if they occur only covariantly
    *  in the value parameter list.
    *  If instantiation of a type parameter fails,
    *  take WildcardType for the proto-type argument.
    *
    *  @param tparams ...
    *  @param formals ...
    *  @param restype ...
    *  @param pt      ...
    *  @return        ...
    */
    def protoTypeArgs(tparams: List[Symbol], formals: List[Type], restpe: Type,
                      pt: Type): List[Type] = {
      /** Map type variable to its instance, or, if `variance' is covariant/contravariant,
       *  to its upper/lower bound */
      def instantiateToBound(tvar: TypeVar, variance: int): Type = try {
        //Console.println("instantiate "+tvar+tvar.constr+" variance = "+variance);//DEBUG
        if (tvar.constr.inst != NoType) {
          instantiate(tvar.constr.inst)
        } else if ((variance & COVARIANT) != 0 && !tvar.constr.hibounds.isEmpty) {
          tvar.constr.inst = glb(tvar.constr.hibounds)
          assertNonCyclic(tvar)//debug
          instantiate(tvar.constr.inst)
        } else if ((variance & CONTRAVARIANT) != 0 && !tvar.constr.lobounds.isEmpty) {
          tvar.constr.inst = lub(tvar.constr.lobounds)
          assertNonCyclic(tvar)//debug
          instantiate(tvar.constr.inst)
        } else if (!tvar.constr.hibounds.isEmpty && !tvar.constr.lobounds.isEmpty &&
                   glb(tvar.constr.hibounds) <:< lub(tvar.constr.lobounds)) {
          tvar.constr.inst = glb(tvar.constr.hibounds)
          assertNonCyclic(tvar)//debug
          instantiate(tvar.constr.inst)
        } else {
          WildcardType
        }
      } catch {
        case ex: NoInstance => WildcardType
      }
      val tvars = tparams map freshVar
      if (isWeaklyCompatible(restpe.instantiateTypeParams(tparams, tvars), pt))
        List.map2(tparams, tvars) ((tparam, tvar) =>
          instantiateToBound(tvar, varianceInTypes(formals)(tparam)))
      else
        tvars map (tvar => WildcardType)
    }

    /** Return inferred type arguments, given type parameters, formal parameters,
    *  argument types, result type and expected result type.
    *  If this is not possible, throw a <code>NoInstance</code> exception.
    *  Undetermined type arguments are represented by `definitions.AllClass.tpe'.
    *  No check that inferred parameters conform to their bounds is made here.
    *
    *  @param   tparams         the type parameters of the method
    *  @param   formals         the value parameter types of the method
    *  @param   restp           the result type of the method
    *  @param   argtpes         the argument types of the application
    *  @param   pt              the expected return type of the application
    *  @param   uninstantiated  a listbuffer receiving all uninstantiated type parameters
    *                           (type parameters mapped by the constraint solver to `scala.All'
    *                           and not covariant in <code>restpe</code> are taken to be
    *                           uninstantiated. Maps all those type arguments to their
    *                           corresponding type parameters).
    *  @return                  ...
    *  @throws                  NoInstance
    */
    // bq: was private, but need it for unapply checking
    def methTypeArgs(tparams: List[Symbol], formals: List[Type], restpe: Type,
                             argtpes: List[Type], pt: Type,
                             uninstantiated: ListBuffer[Symbol]): List[Type] = {
      val tvars = tparams map freshVar
      if (formals.length != argtpes.length) {
        throw new NoInstance("parameter lists differ in length")
      }
      // check first whether type variables can be fully defined from
      // expected result type.
      if (!isWeaklyCompatible(restpe.instantiateTypeParams(tparams, tvars), pt)) {
        throw new DeferredNoInstance(() =>
          "result type " + normalize(restpe) + " is incompatible with expected type " + pt)
      }
      for (tvar <- tvars)
        if (!isFullyDefined(tvar)) tvar.constr.inst = NoType

      // Then define remaining type variables from argument types.
      List.map2(argtpes, formals) {(argtpe, formal) =>
        if (!isCompatible(argtpe.deconst.instantiateTypeParams(tparams, tvars),
                          formal.instantiateTypeParams(tparams, tvars))) {
          if (settings.explaintypes.value)
            explainTypes(argtpe.deconst.instantiateTypeParams(tparams, tvars), formal.instantiateTypeParams(tparams, tvars))
          throw new DeferredNoInstance(() =>
            "argument expression's type is not compatible with formal parameter type" +
            foundReqMsg(argtpe.deconst.instantiateTypeParams(tparams, tvars), formal.instantiateTypeParams(tparams, tvars)))
        }
        ()
      }
      val targs = solvedTypes(tvars, tparams, tparams map varianceInTypes(formals), false)
      List.map2(tparams, targs) {(tparam, targ) =>
        if (targ.typeSymbol == AllClass && (varianceInType(restpe)(tparam) & COVARIANT) == 0) {
          uninstantiated += tparam
          tparam.tpe  //@M TODO: might be affected by change to tpe in Symbol
        } else targ.widen
      }
//    println("meth type args "+", tparams = "+tparams+", formals = "+formals+", restpe = "+restpe+", argtpes = "+argtpes+", underlying = "+(argtpes map (_.widen))+", pt = "+pt+", uninstantiated = "+uninstantiated.toList+", result = "+res) //DEBUG
    }

    /** Is there an instantiation of free type variables <code>undetparams</code>
     *  such that function type <code>ftpe</code> is applicable to
     *  <code>argtpes</code> and its result conform to <code>pt</code>?
     *
     *  @param undetparams ...
     *  @param ftpe        ...
     *  @param argtpes     ...
     *  @param pt          ...
     *  @return            ...
     */
    def isApplicable(undetparams: List[Symbol], ftpe: Type,
                     argtpes0: List[Type], pt: Type): boolean =
      ftpe match {
        case MethodType(formals0, _) =>
          val formals = formalTypes(formals0, argtpes0.length)
          val argtpes = actualTypes(argtpes0, formals.length)
          val restpe = ftpe.resultType(argtpes)
          if (undetparams.isEmpty) {
            (formals.length == argtpes.length &&
             isCompatible(argtpes, formals) &&
             isWeaklyCompatible(restpe, pt))
          } else {
            try {
              val uninstantiated = new ListBuffer[Symbol]
              val targs = methTypeArgs(undetparams, formals, restpe, argtpes, pt, uninstantiated)
              (exprTypeArgs(uninstantiated.toList, restpe.instantiateTypeParams(undetparams, targs), pt) ne null) &&
              isWithinBounds(NoPrefix, NoSymbol, undetparams, targs)
            } catch {
              case ex: NoInstance => false
            }
          }
        case PolyType(tparams, restpe) =>
          val tparams1 = cloneSymbols(tparams)
          isApplicable(tparams1 ::: undetparams, restpe.substSym(tparams, tparams1), argtpes0, pt)
        case ErrorType =>
          true
        case _ =>
          false
      }

    def isApplicableSafe(undetparams: List[Symbol], ftpe: Type, argtpes0: List[Type], pt: Type): boolean = {
      val reportAmbiguousErrors = context.reportAmbiguousErrors
      context.reportAmbiguousErrors = false
      try {
        isApplicable(undetparams, ftpe, argtpes0, pt)
      } catch {
        case ex: TypeError =>
          false
      } finally {
        context.reportAmbiguousErrors = reportAmbiguousErrors
      }
    }

    /** Does type <code>ftpe1</code> specialize type <code>ftpe2</code>
     *  when both are alternatives in an overloaded function?
     *
     *  @param ftpe1 ...
     *  @param ftpe2 ...
     *  @return      ...
     */
    def specializes(ftpe1: Type, ftpe2: Type): boolean = ftpe1 match {
      case MethodType(formals, _) =>
        isApplicable(List(), ftpe2, formals, WildcardType)
      case PolyType(tparams, MethodType(formals, _)) =>
        isApplicable(List(), ftpe2, formals, WildcardType)
      case ErrorType =>
        true
      case _ =>
        false
    }

    /** Is type `tpe1' a strictly better expression alternative than type `tpe2'?
     */
    def isStrictlyBetterExpr(tpe1: Type, tpe2: Type) = {
      def isNullary(tpe: Type) = tpe.paramSectionCount == 0 || tpe.paramTypes.isEmpty
      isNullary(tpe1) && !isNullary(tpe2) ||
      isStrictlyBetter(tpe1, tpe2)
    }

    /** Is type `tpe1' a strictly better alternative than type `tpe2'?
     */
    def isStrictlyBetter(tpe1: Type, tpe2: Type) =
      specializes(tpe1, tpe2) && !specializes(tpe2, tpe1)

    /** error if arguments not within bounds. */
    def checkBounds(pos: Position, pre: Type, owner: Symbol,
                    tparams: List[Symbol], targs: List[Type], prefix: String) = {
      //@M validate variances & bounds of targs wrt variances & bounds of tparams
      //@M TODO: better place to check this?
      //@M TODO: errors for getters & setters are reported separately
      val kindErrors = checkKindBounds(tparams, targs, pre, owner)

      if(!kindErrors.isEmpty)
        error(pos,
          prefix + "the kinds of the type arguments " + targs.mkString("(", ",", ")") +
          " do not conform to the expected kinds of the type parameters "+ tparams.mkString("(", ",", ")") + tparams.head.locationString+ "." +
          kindErrors.toList.mkString("\n", ", ", ""))
      else if (!isWithinBounds(pre, owner, tparams, targs)) {
        if (!(targs exists (_.isErroneous)) && !(tparams exists (_.isErroneous))) {
          error(pos,
                prefix + "type arguments " + targs.mkString("[", ",", "]") +
                " do not conform to " + tparams.head.owner + "'s type parameter bounds " +
                (tparams map (_.defString)).mkString("[", ",", "]"))
        }
        if (settings.explaintypes.value) {
          val bounds = tparams map (tp => tp.info.instantiateTypeParams(tparams, targs).bounds)
          List.map2(targs, bounds)((targ, bound) => explainTypes(bound.lo, targ))
          List.map2(targs, bounds)((targ, bound) => explainTypes(targ, bound.hi))
          ()
        }
      }
    }

    /** Check whether <arg>sym1</arg>'s variance conforms to <arg>sym2</arg>'s variance
     *
     * If <arg>sym2</arg> is invariant, <arg>sym1</arg>'s variance is irrelevant. Otherwise they must be equal.
     */
    def variancesMatch(sym1: Symbol, sym2: Symbol): boolean = (sym2.variance==0 || sym1.variance==sym2.variance)

    /** Check well-kindedness of type application (assumes arities are already checked) -- @M
     *
     * This check is also performed when abstract type members become concrete (aka a "type alias") -- then tparams.length==1
     * (checked one type member at a time -- in that case, prefix is the name of the type alias)
     *
     * Type application is just like value application: it's "contravariant" in the sense that
     * the type parameters of the supplied type arguments must conform to the type parameters of
     * the required type parameters:
     *   - their bounds must be less strict
     *   - variances must match (here, variances are absolute, the variance of a type parameter does not influence the variance of its higher-order parameters)
     *   - @M TODO: are these conditions correct,sufficient&necessary?
     *
     *  e.g. class Iterable[t, m[+x <: t]] --> the application Iterable[Int, List] is okay, since
     *       List's type parameter is also covariant and its bounds are weaker than <: Int
     */
    def checkKindBounds(tparams: List[Symbol], targs: List[Type], pre: Type, owner: Symbol): List[String] = {
      def transform(tp: Type, clazz: Symbol): Type = tp.asSeenFrom(pre, clazz) // instantiate type params that come from outside the abstract type we're currently checking

      // check that the type parameters <arg>hkargs</arg> to a higher-kinded type conform to the expected params <arg>hkparams</arg>
      def checkKindBoundsHK(hkargs: List[Symbol], arg: Symbol, param: Symbol, paramowner: Symbol): (List[(Symbol, Symbol)], List[(Symbol, Symbol)], List[(Symbol, Symbol)]) = {
// NOTE: sometimes hkargs != arg.typeParams, the symbol and the type may have very different type parameters
        val hkparams = param.typeParams

        if(hkargs.length != hkparams.length) {
          if(arg == AnyClass || arg == AllClass) (Nil, Nil, Nil) // Any and Nothing are kind-overloaded
          else (List((arg, param)), Nil, Nil)
        } else {
          val _arityMismatches = new ListBuffer[(Symbol, Symbol)]
          val _varianceMismatches = new ListBuffer[(Symbol, Symbol)]
          val _stricterBounds = new ListBuffer[(Symbol, Symbol)]
          def varianceMismatch(a: Symbol, p: Symbol): unit = _varianceMismatches += (a, p)
          def stricterBound(a: Symbol, p: Symbol): unit = _stricterBounds += (a, p)
          def arityMismatches(as: Iterable[(Symbol, Symbol)]): unit = _arityMismatches ++= as
          def varianceMismatches(as: Iterable[(Symbol, Symbol)]): unit = _varianceMismatches ++= as
          def stricterBounds(as: Iterable[(Symbol, Symbol)]): unit = _stricterBounds ++= as

          for ((hkarg, hkparam) <- hkargs zip hkparams) {
            if (hkparam.typeParams.isEmpty) { // base-case: kind *
              if (!variancesMatch(hkarg, hkparam))
                varianceMismatch(hkarg, hkparam)

              // instantiateTypeParams(tparams, targs) --> higher-order bounds may contain references to type arguments
              // substSym(hkparams, hkargs) --> these types are going to be compared as types of kind *
              //    --> their arguments use different symbols, but are conceptually the same
              //        (could also replace the types by polytypes, but can't just strip the symbols, as ordering is lost then)
              if (!(transform(hkparam.info.instantiateTypeParams(tparams, targs).bounds.substSym(hkparams, hkargs), paramowner) <:< transform(hkarg.info.bounds, owner)))
                stricterBound(hkarg, hkparam)
            } else {
              val (am, vm, sb) = checkKindBoundsHK(hkarg.typeParams, hkarg, hkparam, paramowner)
              arityMismatches(am)
              varianceMismatches(vm)
              stricterBounds(sb)
            }
          }

          (_arityMismatches.toList, _varianceMismatches.toList, _stricterBounds.toList)
        }
      }

      // @M TODO this method is duplicated all over the place (varianceString)
      def varStr(s: Symbol): String =
        if (s.isCovariant) "covariant"
        else if (s.isContravariant) "contravariant"
        else "invariant";

      def qualify(a0: Symbol, b0: Symbol): String = if(a0.toString != b0.toString) "" else {
        assert((a0 ne b0) && (a0.owner ne b0.owner));
        var a = a0; var b = b0
        while (a.owner.name == b.owner.name) { a = a.owner; b = b.owner}
        if (a.locationString ne "") " (" + a.locationString.trim + ")" else ""
      }

      val errors = new ListBuffer[String]
      (tparams zip targs).foreach{ case (tparam, targ) if(targ.isHigherKinded || !tparam.typeParams.isEmpty) => //println("check: "+(tparam, targ))
        val (arityMismatches, varianceMismatches, stricterBounds) =
          checkKindBoundsHK(targ.typeParams, targ.typeSymbolDirect, tparam, tparam.owner) // NOTE: *not* targ.typeSymbol, which normalizes
            // NOTE 2: must use the typeParams of the type targ, not the typeParams of the symbol of targ!!

        if (!(arityMismatches.isEmpty && varianceMismatches.isEmpty && stricterBounds.isEmpty)){
          errors += (targ+"'s type parameters do not match "+tparam+"'s expected parameters: "+
            (for ((a, p) <- arityMismatches)
             yield a+qualify(a,p)+ " has "+reporter.countElementsAsString(a.typeParams.length, "type parameter")+", but "+
              p+qualify(p,a)+" has "+reporter.countAsString(p.typeParams.length)).toList.mkString(", ") +
            (for ((a, p) <- varianceMismatches)
             yield a+qualify(a,p)+ " is "+varStr(a)+", but "+
              p+qualify(p,a)+" is declared "+varStr(p)).toList.mkString(", ") +
            (for ((a, p) <- stricterBounds)
              yield a+qualify(a,p)+"'s bounds "+a.info+" are stricter than "+
              p+qualify(p,a)+"'s declared bounds "+p.info).toList.mkString(", "))
        }
       // case (tparam, targ) => println("no check: "+(tparam, targ, tparam.typeParams.isEmpty))
       case _ =>
      }

      errors.toList
    }

    /** Substitite free type variables `undetparams' of polymorphic argument
     *  expression `tree', given two prototypes `strictPt', and `lenientPt'.
     *  `strictPt' is the first attempt prototype where type parameters
     *  are left unchanged. `lenientPt' is the fall-back prototype where type
     *  parameters are replaced by `WildcardType's. We try to instantiate
     *  first to `strictPt' and then, if this fails, to `lenientPt'. If both
     *  attempts fail, an error is produced.
     */
    def inferArgumentInstance(tree: Tree, undetparams: List[Symbol],
                              strictPt: Type, lenientPt: Type): unit = {
      var targs = exprTypeArgs(undetparams, tree.tpe, strictPt)
      if (targs eq null) targs = exprTypeArgs(undetparams, tree.tpe, lenientPt)
      substExpr(tree, undetparams, targs, lenientPt)
    }

    /** Substitite free type variables `undetparams; of polymorphic expression
     *  <code>tree</code>, given prototype <code>pt</code>.
     *
     *  @param tree ...
     *  @param undetparams ...
     *  @param pt ...
     */
    def inferExprInstance(tree: Tree, undetparams: List[Symbol], pt: Type): unit =
      substExpr(tree, undetparams, exprTypeArgs(undetparams, tree.tpe, pt), pt)

    /** Substitite free type variables `undetparams' of polymorphic argument
     *  expression <code>tree</code> to `targs', Error if `targs' is null
     *
     *  @param tree ...
     *  @param undetparams ...
     *  @param targs ...
     *  @param pt ...
     */
    private def substExpr(tree: Tree, undetparams: List[Symbol],
                          targs: List[Type], pt: Type) {
      if (targs eq null) {
        if (!tree.tpe.isErroneous && !pt.isErroneous)
          error(tree.pos, "polymorphic expression cannot be instantiated to expected type" +
                foundReqMsg(PolyType(undetparams, skipImplicit(tree.tpe)), pt))
      } else {
        new TreeTypeSubstituter(undetparams, targs).traverse(tree)
      }
    }

    /** Substitite free type variables <code>undetparams</code> of application
     *  <code>fn(args)</code>, given prototype <code>pt</code>.
     *
     *  @param fn          ...
     *  @param undetparams ...
     *  @param args        ...
     *  @param pt          ...
     *  @return            Return the list of type parameters that remain uninstantiated.
     */
    def inferMethodInstance(fn: Tree, undetparams: List[Symbol],
                            args: List[Tree], pt: Type): List[Symbol] = fn.tpe match {
      case MethodType(formals0, _) =>
        try {
          val formals = formalTypes(formals0, args.length)
          val argtpes = actualTypes(args map (_.tpe.deconst), formals.length)
          val restpe = fn.tpe.resultType(argtpes)
          val uninstantiated = new ListBuffer[Symbol]
          val targs = methTypeArgs(undetparams, formals, restpe, argtpes, pt, uninstantiated)
          checkBounds(fn.pos, NoPrefix, NoSymbol, undetparams, targs, "inferred ")
          //Console.println("UNAPPLY subst type "+undetparams+" to "+targs+" in "+fn+" ( "+args+ ")")
          val treeSubst = new TreeTypeSubstituter(undetparams, targs)
          treeSubst.traverse(fn)
          treeSubst.traverseTrees(args)
          //Console.println("UNAPPLY gives "+fn+" ( "+args+ "), argtpes = "+argtpes+", pt = "+pt)
          uninstantiated.toList
        } catch {
          case ex: NoInstance =>
            errorTree(fn,
              "no type parameters for " +
              applyErrorMsg(
                fn, " exist so that it can be applied to arguments ",
                args map (_.tpe.widen), WildcardType) +
              "\n --- because ---\n" + ex.getMessage())
            List()
        }
    }

    /** Is intersection of given types populated? That is,
     *  for all types tp1, tp2 in intersection
     *    for all common base classes bc of tp1 and tp2
     *      let bt1, bt2 be the base types of tp1, tp2 relative to class bc
     *      Then:
     *        bt1 and bt2 have the same prefix, and
     *        any correspondiong non-variant type arguments of bt1 and bt2 are the same
     */
    def isPopulated(tp1: Type, tp2: Type): boolean = {
      def isConsistent(tp1: Type, tp2: Type): boolean = (tp1, tp2) match {
        case (TypeRef(pre1, sym1, args1), TypeRef(pre2, sym2, args2)) =>
          assert(sym1 == sym2)
          pre1 =:= pre2 &&
          !(List.map3(args1, args2, sym1.typeParams) {
            (arg1, arg2, tparam) =>
              //if (tparam.variance == 0 && !(arg1 =:= arg2)) Console.println("inconsistent: "+arg1+"!="+arg2)//DEBUG
            tparam.variance != 0 || arg1 =:= arg2
          } contains false)
      }
      if (tp1.typeSymbol.isClass && tp1.typeSymbol.hasFlag(FINAL))
        tp1 <:< tp2 || isNumericValueClass(tp1.typeSymbol) && isNumericValueClass(tp2.typeSymbol)
      else tp1.baseClasses forall (bc =>
        tp2.closurePos(bc) < 0 || isConsistent(tp1.baseType(bc), tp2.baseType(bc)))
    }

    /** Type with all top-level occurrences of abstract types replaced by their bounds */
    def widen(tp: Type): Type = tp match { // @M don't normalize here (compiler loops on pos/bug1090.scala )
      case TypeRef(_, sym, _) if sym.isAbstractType =>
        widen(tp.bounds.hi)
      case TypeRef(_, sym, _) if sym.isAliasType =>
        widen(tp.normalize)
      case rtp @ RefinedType(parents, decls) =>
        copyRefinedType(rtp, List.mapConserve(parents)(widen), decls)
      case _ =>
        tp
    }

    /** Substitite free type variables <code>undetparams</code> of type constructor
     *  <code>tree</code> in pattern, given prototype <code>pt</code>.
     *
     *  @param tree        ...
     *  @param undetparams ...
     *  @param pt          ...
     */
    def inferConstructorInstance(tree: Tree, undetparams: List[Symbol], pt: Type): unit = {
      var restpe = tree.tpe.finalResultType
      var tvars = undetparams map freshVar

      /** Compute type arguments for undetermined params and substitute them in given tree.
       */
      def computeArgs =
        try {
          val targs = solvedTypes(tvars, undetparams, undetparams map varianceInType(restpe), true)
          checkBounds(tree.pos, NoPrefix, NoSymbol, undetparams, targs, "inferred ")
          new TreeTypeSubstituter(undetparams, targs).traverse(tree)
        } catch {
          case ex: NoInstance =>
            errorTree(tree, "constructor of type " + restpe +
                      " can be instantiated in more than one way to expected type " + pt +
                      "\n --- because ---\n" + ex.getMessage())
        }
      def instError = {
        if (settings.debug.value) Console.println("ici " + tree + " " + undetparams + " " + pt)
        if (settings.explaintypes.value) explainTypes(restpe.instantiateTypeParams(undetparams, tvars), pt)
        errorTree(tree, "constructor cannot be instantiated to expected type" +
                  foundReqMsg(restpe, pt))
      }
      if (restpe.instantiateTypeParams(undetparams, tvars) <:< pt) {
        computeArgs
      } else if (isFullyDefined(pt)) {
        if (settings.debug.value) log("infer constr " + tree + ":" + restpe + ", pt = " + pt)
        var ptparams = freeTypeParamsOfTerms.collect(pt)
        if (settings.debug.value) log("free type params = " + ptparams)
        val ptWithWildcards = pt.instantiateTypeParams(ptparams, ptparams map (ptparam => WildcardType))
        tvars = undetparams map freshVar
        if (restpe.instantiateTypeParams(undetparams, tvars) <:< ptWithWildcards) {
          computeArgs
          restpe = skipImplicit(tree.tpe.resultType)
          if (settings.debug.value) log("new tree = " + tree + ":" + restpe)
          val ptvars = ptparams map freshVar
          val pt1 = pt.instantiateTypeParams(ptparams, ptvars)
          if (isPopulated(restpe, pt1)) {
            ptvars foreach instantiateTypeVar
          } else { if (settings.debug.value) Console.println("no instance: "); instError }
        } else { if (settings.debug.value) Console.println("not a subtype " + restpe.instantiateTypeParams(undetparams, tvars) + " of " + ptWithWildcards); instError }
      } else { if (settings.debug.value) Console.println("not fuly defined: " + pt); instError }
    }

    def instantiateTypeVar(tvar: TypeVar) = {
      val tparam = tvar.origin.typeSymbol
      if (false &&
          tvar.constr.inst != NoType &&
          isFullyDefined(tvar.constr.inst) &&
          (tparam.info.bounds containsType tvar.constr.inst)) {
        context.nextEnclosing(_.tree.isInstanceOf[CaseDef]).pushTypeBounds(tparam)
        tparam setInfo tvar.constr.inst
        tparam resetFlag DEFERRED
        if (settings.debug.value) log("new alias of " + tparam + " = " + tparam.info)
      } else {
        val instType = toOrigin(tvar.constr.inst)
        val (loBounds, hiBounds) =
          if (instType != NoType && isFullyDefined(instType)) (List(instType), List(instType))
          else (tvar.constr.lobounds, tvar.constr.hibounds)
        val lo = lub(tparam.info.bounds.lo :: loBounds map toOrigin)
        val hi = glb(tparam.info.bounds.hi :: hiBounds map toOrigin)
        if (!(lo <:< hi)) {
          if (settings.debug.value) log("inconsistent: "+tparam+" "+lo+" "+hi)
        } else if (!((lo <:< tparam.info.bounds.lo) && (tparam.info.bounds.hi <:< hi))) {
          context.nextEnclosing(_.tree.isInstanceOf[CaseDef]).pushTypeBounds(tparam)
          tparam setInfo mkTypeBounds(lo, hi)
          if (settings.debug.value) log("new bounds of " + tparam + " = " + tparam.info)
        } else {
          if (settings.debug.value) log("redundant: "+tparam+" "+tparam.info+"/"+lo+" "+hi)
        }
      }
    }

    def checkCheckable(pos: Position, tp: Type): unit = {
      def patternWarning(tp: Type, prefix: String) =
        context.unit.uncheckedWarning(pos, prefix+tp+" in type pattern is unchecked since it is eliminated by erasure")
      def isLocalBinding(sym: Symbol) =
        sym.isAbstractType &&
        (sym.name == nme.WILDCARD.toTypeName || {
          val e = context.scope.lookupEntry(sym.name)
          (e ne null) && e.sym == sym && e.owner == context.scope
        })
      tp match {
        case SingleType(pre, _) =>
          checkCheckable(pos, pre)
        case TypeRef(pre, sym, args) =>
          if (sym.isAbstractType)
            patternWarning(tp, "abstract type ")
          else if (sym == AllClass || sym == AllRefClass)
            error(pos, "this type cannot be used in a type pattern")
          else
            for (arg <- args) {
              if (sym == ArrayClass) checkCheckable(pos, arg)
              else arg match {
                case TypeRef(_, sym, _) if isLocalBinding(sym) =>
                  ;
                case _ =>
                  patternWarning(arg, "non variable type-argument ")
              }
            }
          checkCheckable(pos, pre)
        case RefinedType(parents, decls) =>
          if (decls.isEmpty) for (p <- parents) checkCheckable(pos, p)
          else patternWarning(tp, "refinement ")
        case ThisType(_) =>
          ;
        case NoPrefix =>
          ;
        case _ =>
          patternWarning(tp, "type ")
      }
    }

    /** Type intersection of simple type <code>tp1</code> with general
     *  type <code>tp2</code>. The result eliminates some redundancies.
     */
    def intersect(tp1: Type, tp2: Type): Type = {
      if (tp1 <:< tp2) tp1
      else if (tp2 <:< tp1) tp2
      else {
        val reduced2 = tp2 match {
          case rtp @ RefinedType(parents2, decls2) =>
            copyRefinedType(rtp, parents2 filter (p2 => !(tp1 <:< p2)), decls2)
          case _ =>
            tp2
        }
        intersectionType(List(tp1, reduced2))
      }
    }

    def inferTypedPattern(pos: Position, pattp: Type, pt: Type): Type = {
      checkCheckable(pos, pattp)
      if (!(pattp <:< pt)) {
        val tpparams = freeTypeParamsOfTerms.collect(pattp)
        if (settings.debug.value) log("free type params (1) = " + tpparams)
        var tvars = tpparams map freshVar
        var tp = pattp.instantiateTypeParams(tpparams, tvars)
        if (!(tp <:< pt)) {
          tvars = tpparams map freshVar
          tp = pattp.instantiateTypeParams(tpparams, tvars)
          val ptparams = freeTypeParamsOfTerms.collect(pt)
          if (settings.debug.value) log("free type params (2) = " + ptparams)
          val ptvars = ptparams map freshVar
          val pt1 = pt.instantiateTypeParams(ptparams, ptvars)
          if (!isPopulated(tp, pt1)) {
            error(pos, "pattern type is incompatibe with expected type"+foundReqMsg(pattp, pt))
            return pattp
          }
          ptvars foreach instantiateTypeVar
        }
        tvars foreach instantiateTypeVar
      }
      intersect(pt, pattp)
    }

    def inferModulePattern(pat: Tree, pt: Type) =
      if (!(pat.tpe <:< pt)) {
        val ptparams = freeTypeParamsOfTerms.collect(pt)
        if (settings.debug.value) log("free type params (2) = " + ptparams)
        val ptvars = ptparams map freshVar
        val pt1 = pt.instantiateTypeParams(ptparams, ptvars)
        if (pat.tpe <:< pt1)
          ptvars foreach instantiateTypeVar
        else
          error(pat.pos, "pattern type is incompatibe with expected type"+foundReqMsg(pat.tpe, pt))
      }

    object toOrigin extends TypeMap {
      def apply(tp: Type): Type = tp match {
        case TypeVar(origin, _) => origin
        case _ => mapOver(tp)
      }
    }

    abstract class SymCollector extends TypeTraverser {
      private var result: List[Symbol] = _
      protected def includeCondition(sym: Symbol): boolean

      override def traverse(tp: Type): TypeTraverser = {
        tp.normalize match {
          case TypeRef(_, sym, _) =>
            if (includeCondition(sym) && !result.contains(sym)) result = sym :: result
          case _ =>
        }
        mapOver(tp)
        this
      }

      /** Collect all abstract type symbols referred to by type <code>tp</code>.
       *
       *  @param tp ...
       *  @return   ...
       */
      def collect(tp: Type): List[Symbol] = {
        result = List()
        traverse(tp)
        result
      }
    }

    object approximateAbstracts extends TypeMap {
      def apply(tp: Type): Type = tp.normalize match {
        case TypeRef(pre, sym, _) if sym.isAbstractType => WildcardType
        case _ => mapOver(tp)
      }
    }

    /** A traverser to collect type parameters referred to in a type
     */
    object freeTypeParamsOfTerms extends SymCollector {
      protected def includeCondition(sym: Symbol): boolean =
        sym.isAbstractType && sym.owner.isTerm
    }

    object typeRefs extends SymCollector {
      protected def includeCondition(sym: Symbol): boolean = true
    }

    def checkDead(tree: Tree): Tree = {
      if (settings.Xwarndeadcode.value && tree.tpe.typeSymbol == AllClass)
        context.warning (tree.pos, "dead code following this construct")
      tree
    }

    /* -- Overload Resolution ---------------------------------------------- */

    def checkNotShadowed(pos: Position, pre: Type, best: Symbol, eligible: List[Symbol]) =
      if (!phase.erasedTypes)
        for (alt <- eligible) {
          if (alt.owner != best.owner && alt.owner.isSubClass(best.owner))
            error(pos,
                  "erroneous reference to overloaded definition,\n"+
                  "most specific definition is: "+best+best.locationString+" of type "+pre.memberType(best)+
                  ",\nyet alternative definition   "+alt+alt.locationString+" of type "+pre.memberType(alt)+
                  "\nis defined in a subclass")
        }

    /** Assign <code>tree</code> the symbol and type of the alternative which
     *  matches prototype <code>pt</code>, if it exists.
     *  If several alternatives match `pt', take parameterless one.
     *  If no alternative matches `pt', take the parameterless one anyway.
     */
    def inferExprAlternative(tree: Tree, pt: Type): unit = tree.tpe match {
      case OverloadedType(pre, alts) => tryTwice {
        var alts1 = alts filter (alt => isCompatible(pre.memberType(alt), pt))
        if (alts1.isEmpty) alts1 = alts
        def improves(sym1: Symbol, sym2: Symbol): boolean =
          sym2 == NoSymbol ||
          { val tp1 = pre.memberType(sym1)
            val tp2 = pre.memberType(sym2)
            (tp2 == ErrorType ||
             !global.typer.infer.isCompatible(tp2, pt) && global.typer.infer.isCompatible(tp1, pt) ||
             isStrictlyBetterExpr(tp1, tp2)) }
        val best = ((NoSymbol: Symbol) /: alts1) ((best, alt) =>
          if (improves(alt, best)) alt else best)
        val competing = alts1 dropWhile (alt => best == alt || improves(best, alt))
        if (best == NoSymbol) {
          if (settings.debug.value) {
            tree match {
              case Select(qual, _) =>
                Console.println("qual: " + qual + ":" + qual.tpe +
                                   " with decls " + qual.tpe.decls +
                                   " with members " + qual.tpe.members +
                                   " with members " + qual.tpe.member(newTermName("$minus")))
              case _ =>
            }
          }
          typeErrorTree(tree, tree.symbol.tpe, pt)
        } else if (!competing.isEmpty) {
          if (!pt.isErroneous)
            context.ambiguousError(tree.pos, pre, best, competing.head, "expected type " + pt)
          setError(tree)
          ()

        } else {
          val applicable = alts1 filter (alt =>
            global.typer.infer.isCompatible(pre.memberType(alt), pt))
          checkNotShadowed(tree.pos, pre, best, applicable)
          tree.setSymbol(best).setType(pre.memberType(best))
        }
      }
    }

    /** Assign <code>tree</code> the type of an alternative which is applicable
     *  to <code>argtpes</code>, and whose result type is compatible with `pt'.
     *  If several applicable alternatives exist, take the
     *  most specialized one.
     *  If no applicable alternative exists, and pt != WildcardType, try again
     *  with pt = WildcardType.
     *  Otherwise, if there is no best alternative, error.
     */
    def inferMethodAlternative(tree: Tree, undetparams: List[Symbol], argtpes: List[Type], pt: Type): unit = tree.tpe match {
      case OverloadedType(pre, alts) =>
        tryTwice {
          if (settings.debug.value) log("infer method alt " + tree.symbol + " with alternatives " + (alts map pre.memberType) + ", argtpes = " + argtpes + ", pt = " + pt)
          val applicable = alts filter (alt => isApplicable(undetparams, pre.memberType(alt), argtpes, pt))
          def improves(sym1: Symbol, sym2: Symbol) =
            sym2 == NoSymbol || sym2.isError ||
            isStrictlyBetter(pre.memberType(sym1), pre.memberType(sym2))
          val best = ((NoSymbol: Symbol) /: applicable) ((best, alt) =>
            if (improves(alt, best)) alt else best)
          val competing = applicable dropWhile (alt => best == alt || improves(best, alt))
          if (best == NoSymbol) {
            if (pt == WildcardType) {
              errorTree(tree, applyErrorMsg(tree, " cannot be applied to ", argtpes, pt))
            } else {
              inferMethodAlternative(tree, undetparams, argtpes, WildcardType)
            }
          } else if (!competing.isEmpty) {
            if (!(argtpes exists (_.isErroneous)) && !pt.isErroneous)
              context.ambiguousError(tree.pos, pre, best, competing.head,
                                     "argument types " + argtpes.mkString("(", ",", ")") +
                                     (if (pt == WildcardType) "" else " and expected result type " + pt))
            setError(tree)
            ()
          } else {
            checkNotShadowed(tree.pos, pre, best, applicable)
            tree.setSymbol(best).setType(pre.memberType(best))
          }
        }
      case _ =>
    }

    /** Try inference twice, once without views and once with views,
     *  unless views are already disabled.
     *
     *  @param infer ...
     */
    def tryTwice(infer: => unit): unit = {
      if (context.implicitsEnabled) {
        val reportGeneralErrors = context.reportGeneralErrors
        context.reportGeneralErrors = false
        context.implicitsEnabled = false
        try {
          infer
        } catch {
          case ex: CyclicReference =>
            throw ex
          case ex: TypeError =>
            context.reportGeneralErrors = reportGeneralErrors
            context.implicitsEnabled = true
            infer
        }
        context.reportGeneralErrors = reportGeneralErrors
        context.implicitsEnabled = true
      } else infer
    }

    /** Assign <code>tree</code> the type of unique polymorphic alternative
     *  with <code>nparams</code> as the number of type parameters, if it exists.
     *  If several or none such polymorphic alternatives exist, error.
     *
     *  @param tree ...
     *  @param nparams ...
     */
    def inferPolyAlternatives(tree: Tree, argtypes: List[Type]): unit = tree.tpe match {
      case OverloadedType(pre, alts) =>
        val sym0 = tree.symbol filter { alt => alt.typeParams.length == argtypes.length }
        if (sym0 == NoSymbol) {
          error(
            tree.pos,
            if (alts exists (alt => alt.typeParams.length > 0))
              "wrong number of type parameters for " + treeSymTypeMsg(tree)
            else treeSymTypeMsg(tree) + " does not take type parameters")
          return
        }
        if (sym0.hasFlag(OVERLOADED)) {
          val sym = sym0 filter { alt => isWithinBounds(pre, alt.owner, alt.typeParams, argtypes) }
          if (sym == NoSymbol) {
            if (!(argtypes exists (_.isErroneous))) {
              error(
                tree.pos,
                "type arguments " + argtypes.mkString("[", ",", "]") +
                " conform to the bounds of none of the overloaded alternatives of\n "+sym0+
                ": "+sym0.info)
              return
            }
          }
          if (sym.hasFlag(OVERLOADED)) {
            val tparams = new AsSeenFromMap(pre, sym.alternatives.head.owner).mapOver(
              sym.alternatives.head.typeParams)
            val bounds = tparams map (_.tpe)  //@M TODO: might be affected by change to tpe in Symbol
            val tpe =
              PolyType(tparams,
                       OverloadedType(AntiPolyType(pre, bounds), sym.alternatives))
            sym.setInfo(tpe)
            tree.setSymbol(sym).setType(tpe)
          } else {
            tree.setSymbol(sym).setType(pre.memberType(sym))
          }
        } else {
          tree.setSymbol(sym0).setType(pre.memberType(sym0))
        }
    }
  }
}
