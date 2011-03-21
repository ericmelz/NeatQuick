package com.emelz.transfer;

import com.emelz.transfer.TransferUtil;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Launcher
 */
class Launcher {
    public static final String NEAT_DATA_DIR = "/Users/eric/Documents/Neat Library.nrmlib";
    public static final String QUICKEN_DATA_DIR = "/Users/eric/Documents/Quicken Experiments/Quicken One Transaction.quickendata";
    //public static final String QUICKEN_DATA_DIR = "/Users/eric/Documents/Quicken Experiments/Quicken Baseline.quickendata";
    public static void main(String[] args) throws Exception {
        TransferUtil tu = new TransferUtil(NEAT_DATA_DIR, QUICKEN_DATA_DIR);
        tu.doTransfer(null);
    }
}