package scodec

import scala.deriving.Mirror

/** Typeclass that describes type constructors that support the `exmap` operation. */
trait Transform[F[_]] {

  /**
    * Transforms supplied `F[A]` to an `F[B]` using two functions, `A => Attempt[B]` and `B => Attempt[A]`.
    */
  def [A, B](fa: F[A]).exmap(f: A => Attempt[B], g: B => Attempt[A]): F[B]

  /**
    * Transforms supplied `F[A]` to an `F[B]` using the isomorphism described by two functions,
    * `A => B` and `B => A`.
    */
  def [A, B](fa: F[A]).xmap(f: A => B, g: B => A): F[B] =
    fa.exmap(a => Attempt.successful(f(a)), b => Attempt.successful(g(b)))

  /** Curried version of [[xmap]]. */
  inline def [A, B](fa: F[A]).xmapc(f: A => B)(g: B => A): F[B] = fa.xmap(f, g)

  /**
    * Transforms supplied `F[A]` to an `F[B]` using two functions, `A => Attempt[B]` and `B => A`.
    *
    * The supplied functions form an injection from `B` to `A`. Hence, converting a `F[A]` to a `F[B]` converts from
    * a larger to a smaller type. Hence, the name `narrow`.
    */
  def [A, B](fa: F[A]).narrow(f: A => Attempt[B], g: B => A): F[B] =
    fa.exmap(f, b => Attempt.successful(g(b)))

  /**
    * Transforms supplied `F[A]` to an `F[B]` using two functions, `A => B` and `B => Attempt[A]`.
    *
    * The supplied functions form an injection from `A` to `B`. Hence, converting a `F[A]` to a `F[B]` converts from
    * a smaller to a larger type. Hence, the name `widen`.
    */
  def [A, B](fa: F[A]).widen(f: A => B, g: B => Attempt[A]): F[B] =
    fa.exmap(a => Attempt.successful(f(a)), g)

  /**
    * Transforms supplied `F[A]` to an `F[B]` using two functions, `A => B` and `B => Option[A]`.
    *
    * Particularly useful when combined with case class apply/unapply. E.g., `widenOpt(fa, Foo.apply, Foo.unapply)`.
    */
  def [A, B](fa: F[A]).widenOpt(f: A => B, g: B => Option[A]): F[B] =
    fa.exmap(
      a => Attempt.successful(f(a)),
      b => Attempt.fromOption(g(b), Err(s"widening failed: $b"))
    )

  /**
    * Transforms supplied `F[A]` to an `F[B]` using implicitly available evidence that such a transformation
    * is possible.
    *
    * Typical transformations include converting:
    *  - an `F[L]` for some `L <: HList` to/from an `F[CC]` for some case class `CC`, where the types in the case class are
    *    aligned with the types in `L`
    *  - an `F[C]` for some `C <: Coproduct` to/from an `F[SC]` for some sealed class `SC`, where the component types in
    *    the coproduct are the leaf subtypes of the sealed class.
    */
  // def [A](fa: F[A]).as[B](using t: Transformer[A, B]): F[B] = t(fa)(using this)
}

/**
  * Witness operation that supports transforming an `F[A]` to an `F[B]` for all `F` which have a `Transform`
  * instance available.
  */
@annotation.implicitNotFound("""Could not prove that ${A} can be converted to/from ${B}.""")
trait Transformer[A, B] {
  def apply[F[_]: Transform](fa: F[A]): F[B]
}

trait TransformerLowPriority0 {
  protected def toTuple[A, B <: Tuple](a: A)(using m: Mirror.ProductOf[A], ev: m.MirroredElemTypes =:= B): B =
    Tuple.fromProduct(a.asInstanceOf[Product]).asInstanceOf[B]
  
  protected def fromTuple[A, B <: Tuple](b: B)(using m: Mirror.ProductOf[A], ev: m.MirroredElemTypes =:= B): A =
    m.fromProduct(b.asInstanceOf[Product]).asInstanceOf[A]
}

trait TransformerLowPriority extends TransformerLowPriority0 {
  given fromProductWithUnits[A, B <: Tuple, C <: Tuple](using 
    m: Mirror.ProductOf[A],
    ev: m.MirroredElemTypes =:= B,
    du: codecs.DropUnits[C] { type L = B }
  ): Transformer[A, C] =
    new Transformer[A, C] {
      def apply[F[_]: Transform](fa: F[A]): F[C] =
        fa.xmap(a => du.addUnits(toTuple(a)), c => fromTuple(du.removeUnits(c)))
    }

  given fromProductWithUnitsReverse[A, B <: Tuple, C <: Tuple](using 
    m: Mirror.ProductOf[A],
    ev: m.MirroredElemTypes =:= B,
    du: codecs.DropUnits[C] { type L = B }
  ): Transformer[C, A] =
    new Transformer[C, A] {
      def apply[F[_]: Transform](fc: F[C]): F[A] =
        fc.xmap(c => fromTuple(du.removeUnits(c)), a => du.addUnits(toTuple(a)))
    }  
}

/** Companion for [[Transformer]]. */
object Transformer extends TransformerLowPriority {

  /** Identity transformer. */
  given id[A]: Transformer[A, A] = new Transformer[A, A] {
    def apply[F[_]: Transform](fa: F[A]): F[A] = fa
  }

  given fromProduct[A, B <: Tuple](using m: Mirror.ProductOf[A], ev: m.MirroredElemTypes =:= B): Transformer[A, B] =
    new Transformer[A, B] {
      def apply[F[_]: Transform](fa: F[A]): F[B] = fa.xmap(toTuple, fromTuple)
    }

  given fromProductReverse[A, B <: Tuple](using m: Mirror.ProductOf[A], ev: m.MirroredElemTypes =:= B): Transformer[B, A] =
    new Transformer[B, A] {
      def apply[F[_]: Transform](fb: F[B]): F[A] = fb.xmap(fromTuple, toTuple)
    }

  given fromProductSingleton[A, B](using m: Mirror.ProductOf[A], ev: m.MirroredElemTypes =:= B *: Unit): Transformer[A, B] =
    new Transformer[A, B] {
      def apply[F[_]: Transform](fa: F[A]): F[B] = fa.xmap(a => toTuple(a).head, b => fromTuple(b *: ()))
    }

  given fromProductSingletonReverse[A, B](using m: Mirror.ProductOf[A], ev: m.MirroredElemTypes =:= B *: Unit): Transformer[B, A] =
    new Transformer[B, A] {
      def apply[F[_]: Transform](fb: F[B]): F[A] = fb.xmap(b => fromTuple(b *: ()), a => toTuple(a).head)
    }

  // /** Builds a `Transformer[A, B]` where `A` is a coproduct whose component types can be aligned with the coproduct representation of `B`. */
  // implicit def fromGenericWithUnalignedCoproductReverse[B, Repr <: Coproduct, A <: Coproduct](
  //     implicit
  //     gen: Generic.Aux[B, Repr],
  //     toAligned: Align[Repr, A],
  //     fromAligned: Align[A, Repr]
  // ): Transformer[A, B] = new Transformer[A, B] {
  //   def apply[F[_]: Transform](fa: F[A]): F[B] =
  //     fa.xmap(a => gen.from(fromAligned(a)), b => toAligned(gen.to(b)))
  // }
}
