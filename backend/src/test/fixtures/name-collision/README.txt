Authored fixture for RenameCollisionIT. No application or vendor code.

Names.Base.a, Child.a and Peer.a are one virtual-method family. Child.occupied
blocks an attempted family rename to occupied. Nested classes test class-name
scope; other.Placeholder can become Names despite collision.Names existing.

Regenerate with JDK 21 and Android SDK build-tools 35.0.0 from this directory:
  mkdir -p build/classes build/dex
  javac --release 8 -g -d build/classes source/collision/Names.java source/collision/other/Placeholder.java
  d8 --output build/dex build/classes/collision/*.class build/classes/collision/other/*.class
  cp build/dex/classes.dex name-collision.dex

Use the SDK's d8 executable; the committed DEX is generated only from these sources.
