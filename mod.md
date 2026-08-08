# MOD Source Code

# File: build.gradle

```gradle
plugins {
    id 'net.fabricmc.fabric-loom-remap' version "${loom_version}"
    id 'maven-publish'
}

version = project.mod_version
group = project.maven_group

repositories {
    // Essential – Fabric Maven for Fabric API and other mods
    maven {
        name = "Fabric"
        url = "https://maven.fabricmc.net/"
    }
    // If you use other libraries, add them here (e.g., CurseForge, JitPack)
    // mavenCentral() // optional, but can be used for non‑mod dependencies
}

loom {
    splitEnvironmentSourceSets()

    mods {
        "smartclicks" {
            sourceSet sourceSets.main
            sourceSet sourceSets.client
        }
    }
}

dependencies {
    // Minecraft and mappings
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    mappings loom.officialMojangMappings()

    // Fabric Loader
    modImplementation "net.fabricmc:fabric-loader:${project.loader_version}"

    // Fabric API – use a version that actually exists!
    //modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}"
}

processResources {
    def version = project.version
    inputs.property "version", version

    filesMatching("fabric.mod.json") {
        expand "version": version
    }
}

tasks.withType(JavaCompile).configureEach {
    it.options.release = 21
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

jar {
    def projectName = project.name
    inputs.property "projectName", projectName

    from("LICENSE") {
        rename { "${it}_$projectName" }
    }
}

publishing {
    publications {
        create("mavenJava", MavenPublication) {
            from components.java
        }
    }
    // If you need to publish to a custom repo, add it here
}
```

# File: gradle.properties

```properties
# Gradle settings
org.gradle.jvmargs=-Xmx1G
org.gradle.parallel=true
org.gradle.configuration-cache=false

# Fabric Properties
minecraft_version=1.21.1
loader_version=0.19.3
loom_version=1.17-SNAPSHOT

# Mod Properties
mod_version=1.1.0
maven_group=com.ra

# Dependencies – pick a confirmed existing version
fabric_api_version=0.112.0+1.21.1
```

# File: settings.gradle

```gradle
pluginManagement {
	repositories {
		maven {
			name = 'Fabric'
			url = 'https://maven.fabricmc.net/'
		}
		mavenCentral()
		gradlePluginPortal()
	}
}

// Should match your modid
rootProject.name = 'smartclicks'

```

## File: src\client\java\com\ra\client\SmartClicksModClient.java

```java
package com.ra.client;

import net.fabricmc.api.ClientModInitializer;

public class SmartClicksModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // All logic is now in mixins
    }
}
```

## File: src\client\java\com\ra\client\mixin\SmartClicksClientMixin.java

```java
package com.ra.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.HttpURLConnection;
import java.net.URL;

@Mixin(Minecraft.class)
public class SmartClicksClientMixin {
    private static boolean wasLooking = false;

    // Target detection – runs every tick
    @Inject(at = @At("HEAD"), method = "tick")
    private void onTick(CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        if (mc.player == null) return;

        HitResult hit = mc.hitResult;
        boolean isLookingAtPlayer = hit != null &&
                hit.getType() == HitResult.Type.ENTITY &&
                ((EntityHitResult) hit).getEntity() instanceof Player;

        if (isLookingAtPlayer && !wasLooking) {
            sendRequest("http://127.0.0.1:4321/target_locked");
        } else if (!isLookingAtPlayer && wasLooking) {
            sendRequest("http://127.0.0.1:4321/target_unlocked");
        }
        wasLooking = isLookingAtPlayer;
    }

    // Attack detection – intercept startAttack
    @Inject(method = "startAttack", at = @At("HEAD"))
    private void onStartAttack(CallbackInfoReturnable<Boolean> cir) {
        Minecraft mc = (Minecraft) (Object) this;
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY) {
            Entity target = ((EntityHitResult) mc.hitResult).getEntity();
            if (target instanceof Player) {
                sendRequest("http://127.0.0.1:4321/hit");
            }
        }
    }

    private static void sendRequest(String urlString) {
        new Thread(() -> {
            try {
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(1000);
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) {
                System.out.println("SmartClicks HTTP error: " + e.getMessage());
            }
        }).start();
    }
}
```

## File: src\client\resources\smartclicks.client.mixins.json

```json
{
    "required": true,
    "package": "com.ra.client.mixin",
    "compatibilityLevel": "JAVA_21",
    "client": [
        "SmartClicksClientMixin"
    ],
    "injectors": {
        "defaultRequire": 1
    },
    "overwrites": {
        "requireAnnotations": true
    }
}
```

## File: src\main\java\com\ra\SmartClicksMod.java

```java
package com.ra;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SmartClicksMod implements ModInitializer {
	public static final String MOD_ID = "smartclicks";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		LOGGER.info("Hello Fabric world!");
	}
}
```

## File: src\main\java\com\ra\mixin\SmartClicksMixin.java

```java
package com.ra.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public class SmartClicksMixin {
	@Inject(at = @At("HEAD"), method = "loadLevel")
	private void init(CallbackInfo info) {
		// This code is injected into the start of MinecraftServer.loadLevel()V
	}
}
```

## File: src\main\resources\fabric.mod.json

```json
{
	"schemaVersion": 1,
	"id": "smartclicks",
	"version": "${version}",
	"name": "Smart Clicks",
	"description": "This is an expert experimental Smart python+java powered auto clicker, Built for *BedWars*!",
	"authors": [
		"Mr Rahnama!"
	],
	"contact": {
		"homepage": "https://fabricmc.net/",
		"sources": "https://github.com/SilentCoding2026/Minecraft-Smart-Autoclicker"
	},
	"license": "CC0-1.0",
	"icon": "assets/smartclicks/icon.png",
	"environment": "*",
	"entrypoints": {
		"main": [
			"com.ra.SmartClicksMod"
		],
		"client": [
			"com.ra.client.SmartClicksModClient"
		]
	},
	"mixins": [
		"smartclicks.mixins.json",
		{
			"config": "smartclicks.client.mixins.json",
			"environment": "client"
		}
	],
	"depends": {
		"fabricloader": ">=0.19.3",
		"minecraft": "~1.21.11",
		"java": ">=21"
	}
}
```

## File: src\main\resources\smartclicks.mixins.json

```json
{
	"required": true,
	"package": "com.ra.mixin",
	"compatibilityLevel": "JAVA_21",
	"mixins": [
		"SmartClicksMixin"
	],
	"injectors": {
		"defaultRequire": 1
	},
	"overwrites": {
		"requireAnnotations": true
	}
}
```

## File: src\main\resources\assets\SmartClicks\icon.png

```png
PNG

   
IHDR         >a   sBIT|d   sRGB    gAMA  a   	pHYs    (J   tEXtSoftware www.inkscape.org<  iTXtXML:com.adobe.xmp     <?xpacket begin='﻿' id='W5M0MpCehiHzreSzNTczkc9d'?>
<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="uuid:faf5bdd5-ba3d-11da-ad31-d33d75182f1b" xmlns:tiff="http://ns.adobe.com/tiff/1.0/"><tiff:Orientation>1</tiff:Orientation></rdf:Description></rdf:RDF></x:xmpmeta>
<?xpacket end='w'?>,  IDATx^yt}?!ŝ.RȑI"#*jt:nıIiT["nFv"+lQIM$Hby uff=P Vs߻oa50|[3-\^
πߵOgG:;tBWiO`DaSoG^1Mi'^2׮tn'hQAAŬv%.:nWn[!O)$~V)*+ \(d[|Y*Jf{94F癝avvht{b8xU,{6xKzK&bQb(dXғ9^+J-]RM%߆\
-[P	,//#uނR ܩ4| `DQD$5ā<uxX;2vQx}~i,w*I Or.`e:!EiC׳
 Gww?`I^n?
}e
q$$ux?Me3
{?oG#LyۢkEMM-T#;KVk;g-@~D[[;54703AQR0bWʖrDீqM8FӼOeGTBo6=ʉO7kNccc3]]}Ȳk/ 	6aY:G'1_ھ
Aҝw.$qt+e	$Iq&><ļhp[[WcG 	}.<Ʀ٤09oH >0 "!>] $	49um]^~rځΝio]a&*brC&FREOhI5HU,m^{~g~PNl֝~}֭d.tN{x1̾.sP6a`۶d{]E٬ *u6n4
\F44MiVorZ?(~N֭;ذazL+{LDT:;a۶Y?(  sg/k׮GQ|4Qutuy]e~PJdeY5kQ}u(
mmk@].J Y~}?@_.ZZZWT(BS*vd4oSRiO.VdۨB}}%ETb)+NCuu
QSsWƙ4j6lH0ZĊ9XH% \,# :\Dk~:;[\b hML_. wI@
N1\ϔ)Wkw		0e7PSa7C qZ[W;`-e'skornL>u#ss~ѧ~!
kt]GE֭@]]~_]A)_^FWW,DQ\ʛC\AmkZNnM1MM͈<- L<˞XC[[7k;O!fi@uuhqbZ<կs׮]ϧ?yx3<g<uѮ'"`\E@1e7I555s45XA*MM-<oԜ.9<dP*.CD$q<uu
IxT*I(um	8aFbSrEs$	|>_[Yg>瓇kY3qm>UAUUC ΓK:*OhqB 2blls%,8z066#re/A$ff"
'*3,@[`*'O`t-#bdd8pTL
% ١*SLOO{L8"B[@$fggvŠLxSH(  7Wn~i?0rm\4[׉$ȫtP g]Vg?Kv/]N9paOD"SHRN,ko<7TqV$D"S\>ClǨnXc!)ohhd` ;U#I&VA>55tB
5VWaSo Y3՟MնV^,%_]]#VΕtSG!Z\'EDuŠA߰`2e*{VɤjS75-<$y $K$f∾
!"=hB7kP
&_b0YjWȹUH+9W9s?E#e!IXo\ŻW>7DқC/_nh	&`z}c=QUL1.Rȗ 2wg{-}0&zsD"sEE	DLJ0uӱsxBG LD$I$c(`bN&vI269	o @꘩˲(ʍ,˄BHu$ F[
/__D**AW^5I
'6OBF`0#R#T
"2jo]9ruyOx饟3CkV=K`M-#T"Q\U1T LӠ;9]EtUa՗~	}G`rrUVΚ߇U@0
ϥƦڲ/ ]שwT&G<stD{9>ɦEK>N; j\`*^ y/+%˕uĥiP #"5Ư
Dt~!6בk16H|h?;:!˕?߮ݵFLr&H FBu?M?
c۷?I1U#"ՅP=k_SSK[[{Ѷ! qp.@x@$r( ZA%Ȏ
$H"jg9O3] lٲ=
l%" i

lο89͹>xc:,%ڏ\{_?ev*+F XٴinD5Qv?_e-bfJ's\2_2?Z߸q35+NTUs#;vtgŘ|N,CO>ϵ~e|a߾M_PpT ,ohI'fZ]>Hss%®]}-j'
/*BP0agy؊*TU3@oｴf$U;@uuU%+VzقQaN+EA5t]~vﾟ;zXOK*oeٱ[uJa+XDw|~N?~l7B!֬ig00u֬i'
e6υTH] 33!Q~nR0PUUUW躎i	I+Cl<-U.JYH;*Im]l۶k!+!f1]ޛE,"( 0{q۝]B}@$AXVx]u}3-NRU<C444.s'<F,/Ŕ# c'_[cTU&,ORI	y)λ  HdrrÇ_gx$Uto`##9|PVRH~p>K(~Y-ߋ={Ǐ*7[}"ĉ79s-C4Mڛ_8/Yyiy_gbb>a,kT.[!yV)AV[\RSV0}}fr
ޯՋHo {RV04㡇>AKKyKiMM-<',Q2QVC$Iٻw_&r
껻B2,ʧ`
Q~rZ}o `fe+ 
Ȳ:JiNZ 6
Dxc[LMMPKi͕T Cocǎ[5;±cGDVwoz'# q@V{5LMM[e@ "[| 2qS{.88{={ӓ78r{'Z⽤pe=@ͺD> IiZQnVo`&=={UUzk.;@0?%f;99dRz0.d>b%)?BQ NLx߹P(DWW^?@OON!؈TV5	M2Vȗ 2ou]'SHon
c߾
I&RI ==HN![zQI&8Kw$_ty233]t57x{Yz
]]N
C'L3BWVD\l\fW-<fMa
vfϞO0ʪx/N!twgSV4Mfff=Y%+5bhIzLoSUEQnX^CGQRhZX_*vU2}(){oYeMKq&Ig@/y./ |y24tMӲܫfR/:AP$]qٽVȧ ^ޱO"IFG/{g(zϹs+B!&&/ u^ȧ 4Ϝ0M$11Ѽ0MW."9E`㛏0O |x>D&:iWFi_@ S3H][xި_4*@
pBצGѓ*w`#zLAqUU+W\Y6ch C9<QFWbYod	e2ElYzJj_ ZqY+u!%13=;UK^M;Y$z"Gŗ|P( |궲|< P;oc௼|QH 6Ď03ĥiWFR3?Ipxk4S:sGAb.ey-e~B 88\X=IƁ˯RP! `i1A8RMy-IJe#U?6YBw6{YC	SO%"T*P2x=^c!(  Қf0YsiRIWL)f^r^p0QL`
綩f,
 dS\8Lw׋[ cBR"~X!P- o[3APUd27BS
`us׽ƕHǳ&/xŠT5P@|cx<#8Rʻ-9
TT*uÝ= bQCGLRjE) y c3LpJFER$D"#3gNzgN4R7(pm#vN#xvv4|>=UyŤ ^Q0=AQ{mE@E}~y4YCM9 5
SSR+a+E(H$ŢȲw9%e߱ܽ%\&2/dn:Ȳ~R%GETUe~~49sf0צOxl
3_cժTUU%N"aFgpx̨RjM :`u EDUU5MM@yhtaN<k;Vg=r Vޛ u}QT/Hhjj!
{&Q$k<yw=zl>rrZiP8HdH~I PUӧ9vw7VW%ނr!_ ?ނ&mI{{~ MSf$	Y
Ü:v8S	l To@}}6lQSSz:[٭	I89 |Ck%y7ں[AeL3M-
_{faFDE!.299W^AQݞK_+& O[Yo&	dYBӯqah$1癞"033mJvQN
{.T  /ӿ$I,80jx ;,n>ƬIzJ{\Xbpk&HZiY~a=|%E34xYl\[֢ShS$JjeYq筌'Y>ݕu^_    IENDB`
```

