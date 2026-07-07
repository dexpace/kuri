# Package org.dexpace.kuri.bind

Annotation-driven binding of an object graph onto a kuri `Url.Builder`/`Uri.Builder`.

Annotate a root class with `@Url`/`@Uri`, mark members with component annotations
(`@Scheme`, `@Host`, `@Path`, `@Query`, ...), then bind with `KuriBind`.
