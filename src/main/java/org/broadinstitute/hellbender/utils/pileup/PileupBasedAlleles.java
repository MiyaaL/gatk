package org.broadinstitute.hellbender.utils.pileup;

import com.google.common.annotations.VisibleForTesting;
import htsjdk.samtools.*;
import htsjdk.samtools.util.SequenceUtil;
import htsjdk.samtools.util.Tuple;
import htsjdk.variant.variantcontext.Allele;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.VariantContextBuilder;
import org.broadinstitute.hellbender.engine.AlignmentAndReferenceContext;
import org.broadinstitute.hellbender.engine.AlignmentContext;
import org.broadinstitute.hellbender.engine.ReferenceContext;
import org.broadinstitute.hellbender.tools.walkers.haplotypecaller.PileupDetectionArgumentCollection;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.read.GATKRead;

import java.util.*;


/**
 * Helper class for handling pileup allele detection supplement for assembly. This code is analogous but not exactly
 * equivalent to the DRAGEN ColumnwiseDetection approach.
 */
public final class PileupBasedAlleles {

    final static String MISMATCH_BASES_PERCENTAGE_TAG = "MZ";

    /**
     * Accepts the raw per-base pileups stored from the active region detection code and parses them for potential variants
     * that are visible in the pileups but might be dropped from assembly for any number of reasons. The basic algorithm works
     * as follows:
     *  - iterate over every pileup and count alt bases
     *      - (beta) detect insertions overlapping this site (CURRENTLY ONLY WORKS FOR INSERTIONS)
     *  - count "bad" reads as defined by Illumina filtering for pileup detection of variants {@Link #evaluateBadRead}
     *  - For each detected alt, evaluate if the number of alternate bases are sufficient to make the call and make a VariantContext.
     *
     * @param alignmentAndReferenceContextList  List of stored pileups and reference context information where every element is a base from the active region.
     *                                          NOTE: the expectation is that the stored pileups are based off of the ORIGINAL (un-clipped) reads from active region determination.
     * @param args                              Configuration arguments to use for filtering/annotations
     * @param headerForReads                    Header for the reads (only necessary for SAM file conversion)
     * @return A list of variant context objects corresponding to potential variants that pass our heuristics.
     */
    public static Tuple<List<VariantContext>,List<VariantContext>> getPileupVariantContexts(final List<AlignmentAndReferenceContext> alignmentAndReferenceContextList, final PileupDetectionArgumentCollection args, final SAMFileHeader headerForReads) {

        final List<VariantContext> pileupVariantList = new ArrayList<>();
        final List<VariantContext> pileupFilteringVariantsListForAssembly = new ArrayList<>();

        // Iterate over every base
        for(AlignmentAndReferenceContext alignmentAndReferenceContext : alignmentAndReferenceContextList) {
            final AlignmentContext alignmentContext = alignmentAndReferenceContext.getAlignmentContext();
            final ReferenceContext referenceContext = alignmentAndReferenceContext.getReferenceContext();
            final int numOfBases = alignmentContext.size();
            final ReadPileup pileup = alignmentContext.getBasePileup();
            final byte refBase = referenceContext.getBase();

            Map<String, Integer> insertionCounts = new HashMap<>();
            Map<Integer, Integer> deletionCounts = new HashMap<>();

            Map<Byte, Integer> altCounts = new HashMap<>();

            int totalAltReads = 0;
            int totalAltBadReads = 0;
            int totalAltBadAssemblyReads = 0;

            for (PileupElement element : pileup) {
                final byte eachBase = element.getBase();

                // check to see that the base is not ref (and non-deletion) and increment the alt counts (and evaluate if the read is "bad")
                if (refBase != eachBase && eachBase != 'D') {
                    incrementAltCount(eachBase, altCounts);
                    totalAltReads++;
                    // Handle the "badness"
                    if (evaluateBadRead(element.getRead(), referenceContext, args, headerForReads)) {
                        totalAltBadReads++;
                    }
                    if (evaluateBadReadForAssembly(element.getRead(), referenceContext, args, headerForReads)) {
                        totalAltBadAssemblyReads++;
                    }
                }

                // TODO currently this only handles Insertions.
                if (args.detectIndels) {
                    // now look for indels
                    if (element.isBeforeInsertion()) {
                        incrementInsertionCount(element.getBasesOfImmediatelyFollowingInsertion(), insertionCounts);
                        totalAltReads++;

                        //TODO this is possibly double dipping if there are snps adjacent to indels?
                        // Handle the "badness"
                        if (evaluateBadRead(element.getRead(), referenceContext, args, headerForReads)) {
                            totalAltBadReads++;
                        }
                        if (evaluateBadReadForAssembly(element.getRead(), referenceContext, args, headerForReads)) {
                            totalAltBadAssemblyReads++;
                        }
                    }

                    if (element.isBeforeDeletionStart()) {
                        incrementDeletionCount(element.getLengthOfImmediatelyFollowingIndel(), deletionCounts);
                        totalAltReads++;

                        //TODO this is possibly double dipping if there are snps adjacent to indels?
                        // Handle the "badness"
                        if (evaluateBadRead(element.getRead(), referenceContext, args, headerForReads)) {
                            totalAltBadReads++;
                        }
                        if (evaluateBadReadForAssembly(element.getRead(), referenceContext, args, headerForReads)) {
                            totalAltBadAssemblyReads++;
                        }
                    }
                }
            }

            // Evaluate the detected SNP alleles for this site
            List<Allele> alleles = new ArrayList<>();
            alleles.add(Allele.create(referenceContext.getBase(), true));
            final Optional<Map.Entry<Byte, Integer>> maxAlt = altCounts.entrySet().stream().max(Comparator.comparingInt(Map.Entry::getValue));
            if (maxAlt.isPresent()
                    && passesFilters(args, false, numOfBases, totalAltBadReads, totalAltReads, maxAlt.get())) {

                alleles.add(Allele.create(maxAlt.get().getKey()));
                final VariantContextBuilder pileupSNP = new VariantContextBuilder("pileup", alignmentContext.getContig(), alignmentContext.getStart(), alignmentContext.getEnd(), alleles);
                pileupVariantList.add(pileupSNP.make());
            }
            alleles = new ArrayList<>();
            alleles.add(Allele.create(referenceContext.getBase(), true));
            if (maxAlt.isPresent()
                    && failsAssemblyFilters(args, false, numOfBases, totalAltBadAssemblyReads, totalAltReads, maxAlt.get())) {

                alleles.add(Allele.create(maxAlt.get().getKey()));
                final VariantContextBuilder pileupSNP = new VariantContextBuilder("pileup", alignmentContext.getContig(), alignmentContext.getStart(), alignmentContext.getEnd(), alleles);
                pileupFilteringVariantsListForAssembly.add(pileupSNP.make());
            }

            // Evaluate the detected Insertions alleles for this site
            if (args.detectIndels) {
                List<Allele> indelAlleles = new ArrayList<>();
                indelAlleles.add(Allele.create(referenceContext.getBase(), true));
                final Optional<Map.Entry<String, Integer>> maxIns = insertionCounts.entrySet().stream().max(Comparator.comparingInt(Map.Entry::getValue));
                if (maxIns.isPresent()
                        && passesFilters(args, true, numOfBases, totalAltBadReads, totalAltReads, maxIns.get())) {

                    indelAlleles.add(Allele.create((char)referenceContext.getBase() + maxIns.get().getKey()));
                    final VariantContextBuilder pileupInsertion = new VariantContextBuilder("pileup", alignmentContext.getContig(), alignmentContext.getStart(), alignmentContext.getEnd(), indelAlleles);
                    pileupVariantList.add(pileupInsertion.make());
                }

                indelAlleles = new ArrayList<>();
                indelAlleles.add(Allele.create(referenceContext.getBase(), true));
                if (maxIns.isPresent()
                        && failsAssemblyFilters(args, true, numOfBases, totalAltBadAssemblyReads, totalAltReads, maxIns.get())) {

                    indelAlleles.add(Allele.create((char)referenceContext.getBase() + maxIns.get().getKey()));
                    final VariantContextBuilder pileupInsertion = new VariantContextBuilder("pileup", alignmentContext.getContig(), alignmentContext.getStart(), alignmentContext.getEnd(), indelAlleles);
                    pileupFilteringVariantsListForAssembly.add(pileupInsertion.make());
                }
            }

            // Evaluate the detected Deletions alleles for this site
            if (args.detectIndels) {
                List<Allele> indelAlleles = new ArrayList<>();
                indelAlleles.add(Allele.create(referenceContext.getBase(), false));

                final Optional<Map.Entry<Integer, Integer>> maxDel = deletionCounts.entrySet().stream().max(Comparator.comparingInt(Map.Entry::getValue));
                if (maxDel.isPresent()
                        && passesFilters(args, true, numOfBases, totalAltBadReads, totalAltReads, maxDel.get())) {

                    indelAlleles.add(Allele.create(referenceContext.getBases(
                            new SimpleInterval(referenceContext.getContig(),
                                    alignmentContext.getStart(),
                                    alignmentContext.getEnd() + maxDel.get().getKey())),
                            true));
                    final VariantContextBuilder pileupInsertion = new VariantContextBuilder("pileup", alignmentContext.getContig(), alignmentContext.getStart(), alignmentContext.getEnd() + maxDel.get().getKey(), indelAlleles);
                    pileupVariantList.add(pileupInsertion.make());
                }

                indelAlleles = new ArrayList<>();
                indelAlleles.add(Allele.create(referenceContext.getBase(), false));
                if (maxDel.isPresent()
                        && failsAssemblyFilters(args, true, numOfBases, totalAltBadAssemblyReads, totalAltReads, maxDel.get())) {

                    indelAlleles.add(Allele.create(referenceContext.getBases(
                                    new SimpleInterval(referenceContext.getContig(),
                                            alignmentContext.getStart(),
                                            alignmentContext.getEnd() + maxDel.get().getKey())),
                            true));
                    final VariantContextBuilder pileupInsertion = new VariantContextBuilder("pileup", alignmentContext.getContig(), alignmentContext.getStart(), alignmentContext.getEnd() + maxDel.get().getKey(), indelAlleles);
                    pileupFilteringVariantsListForAssembly.add(pileupInsertion.make());
                }
            }
        }

        return new Tuple<>(pileupVariantList, pileupFilteringVariantsListForAssembly);
    }

    /**
     * Apply the filters to discovered alleles
     * - Does it have greater than snpThreshold fraction of bases support in the pileups?
     * - Does it have greater than pileupAbsoluteDepth number of reads supporting it?
     * - Are the reads supporting alts at the site greater than badReadThreshold percent "good"? //TODO evaluate if this is worth doing on a per-allele basis or otherwise
     */
    private static boolean passesFilters(final PileupDetectionArgumentCollection args, boolean indel,  final int numOfBases, final int totalAltBadReads, final int totalAltReads, final Map.Entry<?, Integer> maxAlt) {
        return ((float) maxAlt.getValue() / (float) numOfBases) > (indel ? args.indelThreshold : args.snpThreshold)
                && numOfBases >= args.pileupAbsoluteDepth
                && ((args.badReadThreshold <= 0.0) || (float) totalAltBadReads / (float)totalAltReads <= args.badReadThreshold);
    }

    /**
     * Apply the filters to discovered alleles
     * - Does it have greater than snpThreshold fraction of bases support in the pileups?
     * - Does it have greater than pileupAbsoluteDepth number of reads supporting it?
     * - Are the reads supporting alts at the site greater than badReadThreshold percent "good"? //TODO evaluate if this is worth doing on a per-allele basis or otherwise
     */
    private static boolean failsAssemblyFilters(final PileupDetectionArgumentCollection args, boolean indel, final int numOfBases, final int totalAltBadReads, final int totalAltReads, final Map.Entry<?, Integer> maxAlt) {
        return ((float) maxAlt.getValue() / (float) numOfBases) > (indel ? args.indelThreshold : args.snpThreshold)
                && numOfBases >= args.pileupAbsoluteDepth
                && ((args.assemblyBadReadThreshold <= 0.0) || (float) totalAltBadReads / (float)totalAltReads <= args.assemblyBadReadThreshold);
    }

    /**
     * Based on the illumina PileupDetection filtering code: We apply a number of configurable heuristics to the reads that support
     * alt alleles that may be added and for each read evaluate if its "bad" or not by each of the heurisitcs. Currently they are:
     * - Secondary/SA tag reads are bad
     * - Improperly paired reads are bad
     * - Reads with > 8% per-base edit distance to the reference are bad
     * - Reads 2 std deviations away from the standard insert size are bad (not implemented)
     *
     * @param read
     * @param referenceContext
     * @param args
     * @param headerForRead TODO get rid of this sam record conversion
     * @return true if any of the "badness" heuristics suggest we should consider the read suspect, false otherwise.
     */
    @VisibleForTesting
    static boolean evaluateBadRead(final GATKRead read, final ReferenceContext referenceContext, final PileupDetectionArgumentCollection args, final SAMFileHeader headerForRead) {
        if (args.badReadThreshold <= 0.0) {
            return false;
        }
        if (args.badReadProperPair && !read.isProperlyPaired()) {
            return true;
        }
        if (args.badReadSecondaryOrSupplementary && (read.isSecondaryAlignment() || read.hasAttribute("SA"))) {
            return true;
        }


        //TODO this conversion is really unnecessary. Perhaps we should expose a new SequenceUtil like NM tag calculation?...
        SAMRecord samRecordForRead = read.convertToSAMRecord(headerForRead);

        // Assert that the edit distance for the read is in line
        final Integer mismatchPercentage = read.getAttributeAsInteger(MISMATCH_BASES_PERCENTAGE_TAG);
        Utils.nonNull(mismatchPercentage);
        if ((mismatchPercentage / 1000.0) > args.badReadEditDistance) {
            return true;
        }
//        if (args.badReadEditDistance > 0.0) {
//            final int nmScore;
//            if (! read.hasAttribute("NM")) {
//                nmScore = SequenceUtil.calculateSamNmTag(samRecordForRead, referenceContext.getBases(new SimpleInterval(read)), read.getStart() - 1);
//            } else {
//                nmScore = read.getAttributeAsInteger("NM");
//            }
//            // We adjust the NM score by any indels in the read
//            int adjustedNMScore = nmScore - read.getCigarElements().stream().filter(element -> element.getOperator().isIndel()).mapToInt(CigarElement::getLength).sum();
//            if (adjustedNMScore > (read.getCigarElements().stream().filter(element -> element.getOperator().isAlignment()).mapToInt(CigarElement::getLength).sum() * args.badReadEditDistance)) {
//                return true;
//            }
//        }

        //TODO add threshold descibed by illumina about insert size compared to the average
        if (args.templateLengthStd > 0 && args.templateLengthMean > 0) {
            int templateLength = samRecordForRead.getInferredInsertSize();
            // This is an illumina magic number... Its possible none of this is particularly important for Functional Equivalency.
            if (templateLength < args.templateLengthMean - 2.25 * args.templateLengthStd
                    || templateLength > args.templateLengthMean + 2.25 * args.templateLengthStd) {
                return true;
            }
        }
        return false;
    }

    @VisibleForTesting
    static boolean evaluateBadReadForAssembly(final GATKRead read, final ReferenceContext referenceContext, final PileupDetectionArgumentCollection args, final SAMFileHeader headerForRead) {
        if (args.assemblyBadReadThreshold <= 0.0) {
            return false;
        }
        Utils.nonNull(read.getAttributeAsInteger(MISMATCH_BASES_PERCENTAGE_TAG));
        return (read.getAttributeAsInteger(MISMATCH_BASES_PERCENTAGE_TAG) / 1000.0) > args.assemblyBadReadEditDistance;

//        //TODO this conversion is really unnecessary. Perhaps we should expose a new SequenceUtil like NM tag calculation?...
//        SAMRecord samRecordForRead = read.convertToSAMRecord(headerForRead);
//
//        // Assert that the edit distance for the read is in line
//        if (args.assemblyBadReadEditDistance > 0.0) {
//            final int nmScore;
//            if (! read.hasAttribute("NM")) {
//                nmScore = SequenceUtil.calculateSamNmTag(samRecordForRead, referenceContext.getBases(new SimpleInterval(read)), read.getStart() - 1);
//            } else {
//                nmScore = read.getAttributeAsInteger("NM");
//            }
//            // We adjust the NM score by any indels in the read
//            int adjustedNMScore = nmScore - read.getCigarElements().stream().filter(element -> element.getOperator().isIndel()).mapToInt(CigarElement::getLength).sum();
//            if (adjustedNMScore > (read.getCigarElements().stream().filter(element -> element.getOperator().isAlignment()).mapToInt(CigarElement::getLength).sum() * args.assemblyBadReadEditDistance)) {
//                return true;
//            }
//        }
    }

    private static void incrementInsertionCount(String insertion, Map<String, Integer> insertionCounts){
        insertionCounts.put(insertion,
                insertionCounts.getOrDefault(insertion,0) + 1);
    }

    private static void incrementDeletionCount(Integer deletion, Map<Integer, Integer> insertionCounts){
        insertionCounts.put(deletion,
                insertionCounts.getOrDefault(deletion,0) + 1);
    }

    private static void incrementAltCount(byte base, Map<Byte, Integer> altCounts){
        altCounts.put(base,
                altCounts.getOrDefault(base,0) + 1);
    }


    public static void addMismatchPercentageToRead(final GATKRead read, final SAMFileHeader headerForRead, final ReferenceContext referenceContext) {
        //TODO this conversion is really unnecessary. Perhaps we should expose a new SequenceUtil like NM tag calculation?...
        if (read.hasAttribute(MISMATCH_BASES_PERCENTAGE_TAG)){
            return;
        }

        SAMRecord samRecordForRead = read.convertToSAMRecord(headerForRead);
        final int nmScore;
        if (! read.hasAttribute("NM")) {
            nmScore = SequenceUtil.calculateSamNmTag(samRecordForRead, referenceContext.getBases(new SimpleInterval(read)), read.getStart() - 1);
        } else {
            nmScore = read.getAttributeAsInteger("NM");
        }
        // We adjust the NM score by any indels in the read
        int adjustedNMScore = nmScore - read.getCigarElements().stream().filter(element -> element.getOperator().isIndel()).mapToInt(CigarElement::getLength).sum();

        // We store the percentage as an integer
        read.setAttribute(MISMATCH_BASES_PERCENTAGE_TAG, 1000 * adjustedNMScore / (read.getCigarElements().stream().filter(element -> element.getOperator().isAlignment()).mapToInt(CigarElement::getLength).sum() ));
    }


    // TODO get rid of this sam record conversion and rewrite the cigar munging code
//    /**
//     * Copy of {@link AlignmentUtils.countMismatches}
//     * @param read
//     * @param referenceBases
//     * @param referenceOffset
//     * @param bisulfiteSequence
//     * @param matchAmbiguousRef
//     * @return
//     */
//    public static int countMismatches(final GATKRead read, final byte[] referenceBases, final int referenceOffset,
//                                      final boolean bisulfiteSequence, final boolean matchAmbiguousRef) {
//        try {
//            int mismatches = 0;
//
//            final byte[] readBases = read.getBasesNoCopy();
//
//            for (final AlignmentBlock block : read.get()) {
//                final int readBlockStart = block.getReadStart() - 1;
//                final int referenceBlockStart = block.getReferenceStart() - 1 - referenceOffset;
//                final int length = block.getLength();
//
//                for (int i = 0; i < length; ++i) {
//                    if (!basesMatch(readBases[readBlockStart + i], referenceBases[referenceBlockStart + i],
//                            read.getReadNegativeStrandFlag(), bisulfiteSequence, matchAmbiguousRef)) {
//                        ++mismatches;
//                    }
//                }
//            }
//            return mismatches;
//        } catch (final Exception e) {
//            throw new SAMException("Exception counting mismatches for read " + read, e);
//        }
//    }
}
