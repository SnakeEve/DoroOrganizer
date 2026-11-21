package com.doro.organizer.testapk.repackaging;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/*
 * The fixture of SubtypeRuleR8Test: the shape of a Kotlin multifile facade whose parts R8 9.5.29 moved in the T22 qa
 * crash (DEC-103 (5)). Facade is CollectionsKt; Part0 is the package-private part CollectionsKt__IterablesKt, kept
 * because the test APK calls a facade method it defines; Unused1 and Unused2 are the unused parts
 * CollectionsKt__IteratorsJVMKt and CollectionsKt__IteratorsKt; Part2 is CollectionsKt__MutableCollectionsJVMKt, which
 * R8 renamed to h10 in the root package. Only the class file shapes matter: the test never loads these classes.
 */
public final class Facade extends Part3 {
    private Facade() {}
}

abstract class Part0 { // kept, package-private
    public static int part0(List<String> list) {
        int n = 0;
        for (String s : list) {
            n += s.length();
            if (n > 1000) n -= 7;
        }
        return n;
    }
}

abstract class Unused1 extends Part0 { // all members unused
    public static int unused1(int v) { return v * 7 + 3; }
}

abstract class Unused2 extends Unused1 { // all members unused
    public static int unused2(int v) { return v * 11 + 5; }
}

abstract class Part1 extends Unused2 {
    public static void part1(List<String> list, Comparator<String> order) {
        if (list.size() > 1) java.util.Collections.sort(list, order);
        System.out.println("part1 " + list.size());
    }
}

abstract class Part2 extends Part1 { // an indirect subclass of Part0
    public static int part2(List<String> list) {
        int h = 17;
        for (String s : list) {
            h = h * 31 + s.hashCode();
            System.out.println(h);
        }
        return h;
    }
}

abstract class Part3 extends Part2 { // kept, package-private
    public static boolean part3(Collection<String> to, Iterable<String> from) {
        boolean changed = false;
        for (String s : from) changed |= to.add(s);
        return changed;
    }
}
