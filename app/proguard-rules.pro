# Los métodos nativos se resuelven por nombre desde libknds.so: no hay que renombrarlos.
-keepclasseswithmembernames class com.knds.app.NdsCore {
    native <methods>;
}
-keep class com.knds.app.NdsCore { *; }
