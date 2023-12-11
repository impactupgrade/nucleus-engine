package com.impactupgrade.nucleus.it.util;

import java.text.DecimalFormat;
import java.util.Random;

public class TestUtil {

  // https://stackoverflow.com/questions/4574713/generate-random-number-with-restrictions
  public static String randomPhoneNumber() {
    Random rand = new Random();
    int num1 = (rand.nextInt(7) + 1) * 100 + (rand.nextInt(8) * 10) + rand.nextInt(8);
    int num2 = rand.nextInt(743);
    int num3 = rand.nextInt(10000);

    DecimalFormat df3 = new DecimalFormat("000"); // 3 zeros
    DecimalFormat df4 = new DecimalFormat("0000"); // 4 zeros

    return df3.format(num1) + "-" + df3.format(num2) + "-" + df4.format(num3);
  }
}
