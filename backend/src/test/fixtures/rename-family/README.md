# Rename-family fixture

This small fixture is authored for Jeeves tests and contains no application code.
`Reader.a`, `Base.a`, `Child.a`, and `Peer.a` form one virtual-method family.
`Unrelated.a` has the same signature but does not belong to that family.

JEB renames all four related methods together. `RenameFamilyIT` checks that a
second agent cannot bypass an unsaved ownership claim by selecting a different
member, while the unrelated method remains independent.

To regenerate the checked-in DEX with JDK 21 and Android SDK build-tools 35.0.0:

```sh
build_dir=$(mktemp -d)
javac --release 8 -g -d "$build_dir/classes" source/fixture/RenameFamily.java
mkdir "$build_dir/dex"
"$ANDROID_HOME/build-tools/35.0.0/d8" --output "$build_dir/dex" "$build_dir"/classes/fixture/*.class
cp "$build_dir/dex/classes.dex" rename-family.dex
```

Run those commands from this directory. The temporary directory contains only
this fixture's generated Java classes and DEX.
