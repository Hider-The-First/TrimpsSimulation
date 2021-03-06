import java.util.Random;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TrimpsSimulation {

    public final static int goldenFrequency = 30;
    public final static int blacksmitheryZone = 299;
    public final static double critChance = 0.726;
    public final static double critDamage = 13.7;
    public final static double cellDelay = 0.4;
    public final static double attackDelay = 0.258;
    public final static double okFactor = 0.15;
    public final static double achievementDamage = 14.352;
    public final static double heirloomDamage = 5.7;
    public final static double robotrimpDamage = 7.6;
    public final static double heirloomMetalDrop = 6.04;
    public final static double heirloomMinerEff = 6.12;
    public final static int corruptionStart = 151;
    public final static double turkimpMod = 1.75;
    public final static double turkimpDropMod = (turkimpMod == 1.75) ? 1.249 : 1.166;
    public final static double dropsPerZone = 17;
    public final static double mapSize = 26;
    public final static double dropsPerMap = mapSize / 2d / 3d; // garden drop (1/3 metal) on 1/2 cells
    public final static double minerFraction = 0.99;
    public final static int packrat = 70;
    public final static boolean optimizeMaps = true; // true=optimize maps by doing test sims, false=use fixed grid based on damageFactor
    public final static double armorFraction = 0.01; // fraction of metal spent on armor
    private final static double[] mapOffsets = new double[] { 100, 0.75, 0.5,
            0.2, 0.13, 0.08, 0.05, 0.036, 0.03, 0.0275 };
    private final boolean useCache;
    private Perks perks;
    private double goldenHeliumMod;
    private double goldenBattleMod;
    private double goldenBought;
    private double powMod;
    private double damageMod;
    private double dropMod;
    private double metalMod;
    private double jCMod;
    private double lootingMod;
    private double motiMod;
    private double coordFactor;
    private double popMod;
    private double storageFactor;
    private double buildingDiscount;
    private double equipDiscount;
    private double helium;
    private int mapsRunZone;
    private double time;
    private int zone;
    private double metal;
    private double corruptMod;
    private EquipmentManager eM;
    private PopulationManager pM;
    private ZoneSimulation zoneSimulation;
    private int debug = 0;
    
    // expected dodges per hit for dodge imps
    private final static int dodgeLength = 20;
    public static double expectedDodges = calculateExpectedDodges(dodgeLength);

    // private double[][] zoneStats = new double[100][10];
    public static void main(String[] args) {
        long times = System.nanoTime();
        int[] perks = new int[] {94, 90, 92, 104, 103015, 61405, 26627, 87901, 61, 90, 45};
        Perks p = new Perks(perks);
        TrimpsSimulation tS = new TrimpsSimulation(p.getTSFactors(), false,
                //new AveragedZoneSimulation());
        		new ProbabilisticZoneModel(critChance, critDamage, okFactor));
        double highestHeHr = 0;
        SimulationResult sR = tS.runSimulation();
        System.out.format("Result: zone=%d time=%.3fhr hehr%%=%.3f simtime=%dms%n",
        		sR.zone, sR.hours, 100d * sR.helium / p.getSpentHelium() / sR.hours,
        		(System.nanoTime() - times) / 1000000l);
    }

    public SimulationResult runSimulation() {
    	return runSimulation(debug);
    }
    
    public SimulationResult runSimulation(int debug) {
    	this.debug = debug;
        double highestHeHr = 0;
        while (true) {
            startZone();
            pM.buyCoordinations();
            doMapsAndBuyStuff();
            if (!optimizeMaps) {
            	pM.buyCoordinations();
            	doZone();
            }
            endZone();
            double newHeHr = getHeHr();
            if (newHeHr < highestHeHr) {
                break;
            }
            highestHeHr = newHeHr;
        }
        return new SimulationResult(helium,time/3600,zone);
    }

    public TrimpsSimulation(
    		final double[] tsFactors,
    		final boolean useCache,
            final ZoneSimulation zoneSimulation) {
        this.useCache = useCache;
        powMod = tsFactors[Perks.tsFactor.POWER.ordinal()];
        motiMod = tsFactors[Perks.tsFactor.MOTIVATION.ordinal()];
        popMod = tsFactors[Perks.tsFactor.CARPENTRY.ordinal()];
        lootingMod = tsFactors[Perks.tsFactor.LOOTING.ordinal()];
        coordFactor = tsFactors[Perks.tsFactor.COORDINATED.ordinal()];
        equipDiscount = tsFactors[Perks.tsFactor.ARTISANISTRY.ordinal()];
        buildingDiscount = tsFactors[Perks.tsFactor.RESOURCEFUL.ordinal()];
        eM = new EquipmentManager(equipDiscount);
        pM = new PopulationManager(popMod, buildingDiscount, coordFactor);
        damageMod = achievementDamage
                * 7 // anticipation
                * 4 // dominance
                * robotrimpDamage
                * heirloomDamage
                * powMod;
        storageFactor = 1 - 0.25 * buildingDiscount/(1 + packrat/5d); // storage costs eat into all resources
        metalMod = 0.5 // base per miner
        		* minerFraction * 0.5 // workspaces are half of population
        		* heirloomMinerEff
        		* turkimpMod
        		* (1 - armorFraction)
        		* storageFactor
                * motiMod;
        dropMod = 0.16 // drops are based on 1/6 of population
        		* heirloomMetalDrop
        		* turkimpDropMod
        		* (1 - armorFraction)
        		* storageFactor
                * lootingMod;
        jCMod = metalMod
                * lootingMod;
        helium = 0;
        time = 0;
        zone = 0;
        mapsRunZone = 0;
        metal = 0;
        goldenHeliumMod = 1;
        goldenBattleMod = 1;
        goldenBought = 0;
        corruptMod = 1;
        this.zoneSimulation = zoneSimulation;
    }

    // calculate the expected # of dodges per hit against a dodge imp
    private static double calculateExpectedDodges(final int length) {
        double res = 0;
        double cumChance = 0.7;
        for (int hits = 1; hits < length; hits++) {
            // System.out.println(res + " expected dodges for hit " + (hits-1));
            cumChance *= 0.3;
            res += cumChance;
        }
        return res;
    }
    
    private double getHeHr() {
        return (helium / time * 3600);
    }

    private void startZone() {
        zone++;
        if (zone % goldenFrequency == 0) {
            // TODO code properly
            if (zone <= 530) {
                goldenBought++;
                goldenHeliumMod += goldenBought / 100d;
            } else {
                goldenBought++;
                goldenBattleMod += goldenBought * 3d / 100d;
            }

        }
        if (zone <= blacksmitheryZone) {
            eM.dropAll(zone, blacksmitheryZone);
        }
    }

    private void doMapsAndBuyStuff() {

        double hp = enemyHealth();
        
        if (optimizeMaps) {
	        buyStuff();
	        // todo: optimize upper bound for map checking, probably don't need to check all the way up to 10
	        eM.save();
	        pM.save();
	        double SVmetal = metal;
	        double mapTime = 0;
	        double bestTime = Double.POSITIVE_INFINITY;
	        double bestZoneTime = 0;
	        int maps = 0;

            double damage = damageMod * goldenBattleMod * eM.getTotalDamage()
                    * pM.getDamageFactor();
            double improbHP = hp / .508 * 1.3 * 6;
            int minMaps = Math.max(mapsRunZone - 2,0);
            double minImprobDamage = (damage - improbHP) * okFactor;
	        minMaps += (minImprobDamage < improbHP) ? 1 : 0;
	        while (maps < minMaps) {
	        	mapTime += runMap();
	        	maps++;
	        }
	        mapsRunZone = maps;
	        if (minImprobDamage * (1 + 0.2d * minMaps) >= improbHP) { // this means we are guaranteed 100% overkill, so just calculate the min zone time and be done
	        	bestZoneTime = minZoneTime();
	        	bestTime = bestZoneTime;
	        } else {
	        	while (maps < minMaps + 6) {
		        	if (maps > minMaps) { 
		        		mapTime += runMap(); 
		        	}
		            damage = damageMod * goldenBattleMod * eM.getTotalDamage()
		                    * pM.getDamageFactor() * (1d + 0.2d * maps);
		            double damageFactor = damage / hp;
		            double zoneTime = zoneSimulation.getExpectedTime(cellDelay, attackDelay,
		                    damageFactor, critChance, critDamage, okFactor, corruptMod,
		                    corruptionStart, zone);
		            double totTime = zoneTime + mapTime;
		            if (totTime < bestTime) {
		            	bestTime = totTime;
		            	bestZoneTime = zoneTime;
		            	eM.save();
		            	pM.save();
		            	SVmetal = metal;
		            	mapsRunZone = maps;
		            }
		            maps++;
		        }
		        eM.restore();
		        pM.restore();
		        metal = SVmetal;
	        }
	        time += bestTime;
	        addProduction(bestZoneTime, dropsPerZone, 1);
	        if (debug > 0) {
	        	System.out.format("Zone %d, ran %d maps, total time %.2f, dF=%.3f%n", zone, mapsRunZone, bestTime, damage / hp);
	        }
        } else {
        	mapsRunZone = 0;
            double damage = damageMod * goldenBattleMod * eM.getTotalDamage()
                    * pM.getDamageFactor();
            double damageFactor = damage / hp;
            
	        int mapsToRun = mapsToRun(damageFactor);
	        if (mapsToRun > 0) {
	            for (int x = 0; x < mapsToRun; x++) {
	                runMap();
	            }
	            mapsRunZone = mapsToRun;
	            time += cellDelay * mapSize / 2d * mapsToRun;
	        }
	        buyStuff();
	        //System.out.format("Zone %d, ran %d maps, map time %.2f, dF=%.3f%n", zone, mapsRunZone, mapsRunZone * cellDelay * mapSize / 2d, damageFactor);
        }
    }
    
    private double minZoneTime() {
    	return 50 * cellDelay + attackDelay * getNumCorrupt(zone, corruptionStart) / 12d * expectedDodges;
    }
    
    private double runMap() {
    	eM.dropMap(zone,  blacksmitheryZone);
        // TODO won't always be able to run the best possible map, actually calcuate resources for highest map that can actually be run
    	// -> may need a correction to runtime as well
    	// Chrono/Jest drops
    	metal += jCMod * (5d + 45d/6d) * (mapSize / 33.33d)
    			* 2 // scrying
    			* 1.815 * (0.8 + 0.1 * mapSize / 100d) // 181.5% avg map difficulty, derated because loot is scaled based on 100 cells
    			* pM.getPopulation();
    	addProduction(cellDelay * mapSize / 2d, dropsPerMap, 2);
    	buyStuff();
    	return (cellDelay * mapSize / 2d);
    }
    
    private void buyStuff() {
    	pM.buyStuff(metal / 100d);
    	pM.buyCoordinations();
    	// todo: account for metal spent on armor (5%?)
    	eM.buyStuff(metal * 99 / 100d);
    	metal = 0;
    }

    public static int getNumCorrupt(int zone, int corruptionStart) {
        return (zone < corruptionStart) ? 0 : Math.min(80,
                Math.max(0, (int) ((zone - corruptionStart) / 3) + 2));
    }
    
    private void doZone() {
        double damage = damageMod * goldenBattleMod * eM.getTotalDamage()
                * pM.getDamageFactor() * (1d + 0.2d * mapsRunZone);
        double hp = enemyHealth();
        double damageFactor = damage / hp;
        double res = 0;
        if (useCache) {
            int corrupted = getNumCorrupt(zone, corruptionStart);
            double cachedValue = SimulationCache.getInstance()
                    .getValue(corrupted, damageFactor);
            if (cachedValue == 0) {
                res = zoneSimulation.getExpectedTime(cellDelay, attackDelay,
                        damageFactor, critChance, critDamage, okFactor,
                        corruptMod, corruptionStart, zone);
                SimulationCache.getInstance().setValue(corrupted, damageFactor,
                        res);
            } else {
                res = cachedValue;
            }
        } else {
            // zoneStats = new double[100][10];
            res = zoneSimulation.getExpectedTime(cellDelay, attackDelay,
                    damageFactor, critChance, critDamage, okFactor, corruptMod,
                    corruptionStart, zone);
            if (damageFactor < 10) {
                //double estimate = probZoneSim(damageFactor);
                //for (int i = 0; i < 100; i++) {
                    // System.out.format("sim cell %d stats: eH=%.2f eOK=%.3f
                    // pN=%.3f eOKc=%.2f pC=%.2f fresh=%.2f%n",
                    // i+1, zoneStats[i][0]/zoneStats[i][1],
                    // zoneStats[i][2]/zoneStats[i][3],
                    // zoneStats[i][3]/zoneSimulationRepeatAmount,
                    // zoneStats[i][4]/zoneStats[i][5],
                    // zoneStats[i][5]/zoneSimulationRepeatAmount,
                    // zoneStats[i][6]/zoneSimulationRepeatAmount);
                //}
            }
        }
        time += res;
        addProduction(res, dropsPerZone, 1);
    }
    
    private void addProduction(double time, double nDrops, double scryFactor) {
    	metal += metalMod * time * pM.getPopulation();
        metal += dropMod * nDrops * scryFactor * pM.getPopulation();
    }

    private void endZone() {
        if (zone == 15) {
            metalMod *= 2;
            jCMod *= 2;
            dropMod *= 2;
        }
        if (zone <= 59) {
            metalMod *= 1.25;
            jCMod *= 1.25;
            dropMod *= 1.25;
        } else {
            metalMod *= 1.6;
            jCMod *= 1.6;
            dropMod *= 1.6;
        }
        metalMod *= Math.pow(1.003, 3);
        jCMod *= Math.pow(1.003, 6);
        dropMod *= Math.pow(1.003, 6);
        pM.applyTauntImps(3);
        pM.newCoordination();
        if (zone >= 60 && zone <= 70
                || zone >= 70 && zone <= 78 && zone % 2 == 0
                || zone >= 81 && zone <= 90 && zone % 3 == 0
                || zone >= 95 && zone <= 170 && zone % 5 == 0
                || zone >= 180 && zone % 10 == 0) {
            pM.newGigaStation();
        }
        if (zone >= 20) {
            addHelium();
        }
    }

    private void addHelium() {
        // TODO fix

        double heliumMod = 1;
        if (zone >= 59) {
            heliumMod *= 5;
        }
        if (zone >= corruptionStart) {
            heliumMod *= 2;
        }
        double a = 1.35 * (zone - 19);
        heliumMod = Math.round(heliumMod * Math.pow(1.23, Math.sqrt(a)))
                + Math.round(heliumMod * a);
        if (zone >= 201) {
            heliumMod *= 1.2;
        }
        heliumMod *= Math.pow(1.005, zone);
        heliumMod *= lootingMod;
        heliumMod *= goldenHeliumMod;
        helium += heliumMod * (1 + 0.15 * getNumCorrupt(zone, corruptionStart));
    }

    private double enemyHealth() {
        double res = 0;
        res += 130 * Math.sqrt(zone) * Math.pow(3.265, zone / 2d);
        res -= 110;
        res *= 0.508;
        if (zone >= 60) {
            res *= Math.pow(1.1, zone - 59);
        }
        if (zone >= 151) {
            corruptMod = 10 * Math.pow(1.05, Math.floor((zone - 150) / 6));
            res *= corruptMod;
        }
        return Math.floor(res);
    }

    private int mapsToRun(final double damageFactor) {
        for (int x = 0; x < mapOffsets.length; x++) {
            if (damageFactor > mapOffsets[x]) {
                return x;
            }
        }
        return mapOffsets.length;
    }


}
