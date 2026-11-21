package com.doro.organizer.testapk.repackaging.app;

import com.doro.organizer.testapk.repackaging.Facade;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/* The kept entry point of SubtypeRuleR8Test: it calls every used part through the facade, as app code calls CollectionsKt. */
public class Main {
    public static void main(String[] args) {
        List<String> list = new ArrayList<>(Arrays.asList(args));
        Facade.part1(list, Comparator.naturalOrder());
        Facade.part1(list, Comparator.reverseOrder());
        System.out.println(Facade.part0(list) + Facade.part2(list) + Facade.part2(new ArrayList<>(list)));
        System.out.println(Facade.part3(new ArrayList<>(), list));
    }
}
