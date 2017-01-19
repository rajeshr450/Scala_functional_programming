package org.wartremover
package warts

object PublicInference extends WartTraverser {
  def apply(u: WartUniverse): u.Traverser = {
    import u.universe._
    import u.universe.Flag._

    new u.Traverser {
      override def traverse(tree: Tree): Unit = {
        tree match {
          // Ignore trees marked by SuppressWarnings
          case t if hasWartAnnotation(u)(t) =>

          case t: ValOrDefDef if t.symbol.isPublic &&
              t.symbol.owner.isClass && t.symbol.owner.isPublic &&
              !t.symbol.isConstructor && !t.mods.hasFlag(PARAMACCESSOR | CASEACCESSOR) &&
              !hasTypeAscription(u)(t) && !isSynthetic(u)(tree) =>
            u.error(tree.pos, "Public member must have an explicit type ascription")

          case _ =>
            super.traverse(tree)
        }
      }
    }
  }
}
