//This program is free software: you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation, either version 3 of the License, or
//(at your option) any later version.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License
//along with this program.  If not, see <http://www.gnu.org/licenses/>.

package RBBNPE;

import edu.stanford.nlp.ling.HasWord;
import edu.stanford.nlp.ling.Sentence;
import edu.stanford.nlp.ling.TaggedWord;
import edu.stanford.nlp.ling.Word;
import edu.stanford.nlp.tagger.maxent.MaxentTagger;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by Laurenz Vorderwuelbecke on 05.08.16.
 * @author Laurenz Vorderwuelbecke
 */
public class POSBasedBaseNounPhraseExtractor {

    /**
     * This class represents the rejection rules conveniently
     */
    private class RejectionRule {
        private String key;
        private String operation;
        ArrayList<String> rules;
        public RejectionRule(String key, String operation) {
            this.key = key;
            this.operation = operation;
            this.rules = new ArrayList<String>();
        }
        public void addRule(String newRule) {
            rules.add(newRule);
        }
        public ArrayList<String> getRules() {
            return rules;
        }
        public String getKey() {
            return key;
        }
        public String getOperation() {
            return operation;
        }
    }

    private int lastStartOffset = -1;

    private MaxentTagger POSTagger;
    List<List<TaggedWord>> taggedSentences;
    HashMap<BaseNounPhrase, List<TaggedWord>> dictionaryWithTaggedSentenceForBaseNP;
    ArrayList<BaseNounPhrase> extractedBaseNounPhrases;


    public POSBasedBaseNounPhraseExtractor(String pathToStanfordModel) {
        Properties props = new Properties();
        props.put("tokenize.options", "untokenizable=allKeep,normalizeParentheses=false"); // or noneKeep
        props.put("encoding", "utf-8");
        props.put("strictTreebank3", "true");

        this.POSTagger = new MaxentTagger(pathToStanfordModel, props);
    }


    /**
     * Preprocess data
     */
    private List processString(String text) {
        List<List<HasWord>> sentences = MaxentTagger.tokenizeText(new StringReader(text));
        return sentences;
    }

    /**
     * Tag data with POS Tags
     */
    private List<List<TaggedWord>> tagWithPOSTags(List<List<HasWord>> sentences) {
        List<List<TaggedWord>> taggedSentences = new ArrayList<List<TaggedWord>>();
        for (List<HasWord> sentence : sentences) {
            List<TaggedWord> taggedSentence = POSTagger.tagSentence(sentence);
            taggedSentences.add(taggedSentence);
        }
        return taggedSentences;
    }

    /**
     * Apply positive rules using REGEX
     */
    private ArrayList<BaseNounPhrase> applyPositiveRules(List<List<TaggedWord>> taggedSentences) {

        try {
            ArrayList<BaseNounPhrase> extractedNounPhrases = new ArrayList<BaseNounPhrase>();

            String rulesFilePath = "positiveRules.txt";
            String rulesRegEx = getRegExString(rulesFilePath);

            Pattern baseNPPositiveRulesPattern = Pattern.compile(rulesRegEx);
            Matcher baseNPMatcher = baseNPPositiveRulesPattern.matcher("");

            Pattern POSTagPattern = Pattern.compile("(?<!(?:/|\\)))/([A-Z,$,#,€]{1,4})");
            Matcher POSTagMatcher = POSTagPattern.matcher("");

            for (List<TaggedWord> taggedSentence : taggedSentences) {

                String sentence = Sentence.listToString(taggedSentence, false);
                baseNPMatcher.reset(sentence);

                while(baseNPMatcher.find()) {

                    String baseNPString = baseNPMatcher.group(0);

                    String POSTag = "";
                    POSTagMatcher.reset(baseNPString); //So Matcher does not have to be reinitialized every time

                    while (POSTagMatcher.find()) {
                        POSTag = POSTagMatcher.group(1);  //POS Tag of last token
                    }


                    //String cleanBaseNPString = baseNPString.replace("\\/", "\\");  //CoNLL Data entails \/ to symbolize /. By exchanging it for \, no conflicts can arise when extracting the POS Tags
                    String cleanBaseNPString = baseNPString.replaceAll("(?<!(?:\\/|\\\\))\\/([A-Z,$,#,€]{1,4})", "").trim();
                    //cleanBaseNPString = cleanBaseNPString.replace("\\", "//");

                    if (!cleanBaseNPString.equals("")) {

                        BaseNounPhrase baseNP = createBaseNounPhrase(cleanBaseNPString, baseNPString, taggedSentence, POSTag);

                        dictionaryWithTaggedSentenceForBaseNP.put(baseNP, taggedSentence);
                        extractedNounPhrases.add(baseNP);
                    }


                }
            }

            return extractedNounPhrases;

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }



    /**
     * Apply rejection rules
     */
    private ArrayList<BaseNounPhrase> applyRejectionRules(ArrayList<BaseNounPhrase> baseNounPhrases) {

        try {

            ArrayList<RejectionRule> rules = getRejectionRules("rejectionRules.txt");

            for (RejectionRule rule : rules) {

                String delimiter = rule.getKey(); //The String to seperate the phrase on
                String operation = rule.getOperation();

                lastStartOffset = -1; //So every pass can start from the beginning again

                for (int i = 0; i < baseNounPhrases.size(); i++) {

                    BaseNounPhrase baseNP = baseNounPhrases.get(i);
                    String phrase = baseNP.getPhraseStringWithPOSTags();

                    List<TaggedWord> taggedSentence = dictionaryWithTaggedSentenceForBaseNP.get(baseNP);

                    if (taggedSentence != null) {
                        String taggedSentenceString = Sentence.listToString(taggedSentence, false);

                        if (phrase.contains(delimiter)) {

                            ArrayList<String> checkStrings = rule.getRules();

                            boolean oneRejectionRuleMatched = false;

                            for (String checkString : checkStrings) {

                                String[] parts = phrase.split(delimiter);
                                String partone = parts[0];
                                String parttwo = parts[1];

                                checkString = checkString.replace(";@phrase@;", phrase);
                                checkString = checkString.replace(";@phrasepartone@;", partone);
                                checkString = checkString.replace(";@phraseparttwo@;", parttwo);
                                checkString = checkString.replace("$", "\\$");

                                Pattern baseNPRejectionRulesPattern = Pattern.compile(checkString);
                                Matcher baseNPMatcher = baseNPRejectionRulesPattern.matcher(taggedSentenceString);

                                if (baseNPMatcher.find()) {

                                    oneRejectionRuleMatched = true;

                                    dictionaryWithTaggedSentenceForBaseNP.remove(baseNP);
                                    baseNounPhrases.remove(i);

                                    for (int j = 0; j < parts.length; j++) {

                                        String subString = parts[j];

                                        switch (operation) {
                                            case "keepright":
                                                if (j == parts.length - 1) {
                                                    subString = delimiter + subString;
                                                }
                                                break;
                                            case "keepleft":
                                                if (j == 0) {
                                                    subString = subString + delimiter;
                                                }
                                                break;
                                            default:
                                        }

                                        String cleanSubstring = subString.replaceAll("(?<!/)/[A-Z,$,#,€]{1,4}", "").trim();

                                        BaseNounPhrase newBaseNP = createBaseNounPhrase(cleanSubstring, subString, taggedSentence, "");
                                        baseNounPhrases.add(i + j, newBaseNP);

                                    }

                                    baseNP = null;
                                }
                                if (oneRejectionRuleMatched) {
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        } catch(IOException e){
            e.printStackTrace();
        }
        return baseNounPhrases;
    }

    /**
     * Extracts all base noun phrases from a given text.
     * The text can contain multiple sentences.
     * Results are saved internally and are available for output or saving
     * @param text The input text
     */
    public void extractBaseNounPhrasesFromText(String text) {

        dictionaryWithTaggedSentenceForBaseNP = new HashMap<BaseNounPhrase, List<TaggedWord>>();

        List sentences = processString(text);
        System.out.println("Finished Processing the text");

        System.out.println("Starting tagging");
        taggedSentences = tagWithPOSTags(sentences);
        System.out.println("Finished tagging the text");

        System.out.println("Starting application of positive rules");
        //sentences = null;
        extractedBaseNounPhrases = applyPositiveRules(taggedSentences);
        System.out.println("Finished application of positive rules");

        System.out.println("Starting application of rejection rules");
        lastStartOffset = -1;  //Reset so second pass of finding position in the createBaseNP can work
        extractedBaseNounPhrases = applyRejectionRules(extractedBaseNounPhrases);
        System.out.println("Finished application of rejection rules");

    }

    /**
     * Extracts all base noun phrases from a given file in the CoNLL data format.
     * Results are saved internally and are available for output or saving
     * The tokens have to be in the first column
     * Columns have to be either seperated by a whitespace or a tab
     * @param path absolute path to the CoNLL File
     * @throws IOException
     */
    public void extractBaseNounPhrasesFromCoNLLData(String path) throws IOException {

        dictionaryWithTaggedSentenceForBaseNP = new HashMap<BaseNounPhrase, List<TaggedWord>>();

        List<List<HasWord>> sentences = new ArrayList();
        List<HasWord> sentence = new ArrayList<HasWord>();

        BufferedReader br = new BufferedReader(new FileReader(path));
        String currentLine;
        int currentStartPosition = 0;


        while (null != (currentLine = br.readLine())) {

            if (!currentLine.equals("") && !currentLine.contains("\t\t")) {

                String[] argumentsInLine = currentLine.split(" ");

                if (argumentsInLine.length <= 2) {
                    argumentsInLine = currentLine.split("\t");
                }

                String cleanToken = argumentsInLine[0]/*.replace("\\/", "//")*/;

                int currentEndPosition = currentStartPosition + cleanToken.length() - 1;

                sentence.add(new Word(cleanToken, currentStartPosition, currentEndPosition));

                currentStartPosition = currentEndPosition + 2;

            } else if (currentLine.equals("") || currentLine.equals("\t\t")) {
                sentences.add(sentence);
                sentence = new ArrayList<HasWord>();
            } else {
                System.out.println("Strange Line occured: " + currentLine);
            }
        }
        if (sentence.size() >= 0) {
            sentences.add(sentence); //saves last Sentence, when no empty line follows it
        }

        System.out.println("Finished Processing the text");

        System.out.println("Starting tagging");
        taggedSentences = tagWithPOSTags(sentences);
        System.out.println("Finished tagging the text");

        System.out.println("Starting application of positive rules");
        //sentences = null;
        extractedBaseNounPhrases = applyPositiveRules(taggedSentences);
        System.out.println("Finished application of positive rules");

        System.out.println("Starting application of rejection rules");
        lastStartOffset = -1;  //Reset so second pass of finding position in the createBaseNP can work
        extractedBaseNounPhrases = applyRejectionRules(extractedBaseNounPhrases);
        System.out.println("Finished application of rejection rules");

    }

    /**
     * Returns the previously extracted base noun phrases as a List of BaseNounPhrase Objects
     * @return ArrayList of BaseNounPhrase Objects
     */
    public ArrayList<BaseNounPhrase> getBaseNounPhrases() {
        return extractedBaseNounPhrases;
    }

    /**
     * Writes the previously extracted base noun phrases to the given absolute path in the CoNLL Format
     * 1. Column are the tokens
     * 2. Column are the created POS Tags
     * 3. Column are the chunk tags in the IOB2 format, only with baseNP information
     * @param pathToWrite absolutePath
     * @throws FileNotFoundException
     * @throws UnsupportedEncodingException
     */
    public void writeBaseNounPhrasesAsCoNLLFile(String pathToWrite) throws FileNotFoundException, UnsupportedEncodingException {

        PrintWriter writer = new PrintWriter(pathToWrite, "UTF-8");

        int indexNPs = 0;
        int indexInNP = 0;

        int sizeOfExtractedNPsArray = extractedBaseNounPhrases.size();

        sentenceLoop: for (int indexOfSentences = 0; indexOfSentences < taggedSentences.size(); indexOfSentences++) {

            List<TaggedWord> sentence = taggedSentences.get(indexOfSentences);

            for (int i = 0; i < sentence.size(); i++) {

                if (i == 0 && indexOfSentences != 0) {
                    writer.println(""); //Create space bewteen two sentences
                }
                TaggedWord word = sentence.get(i);

                String token = word.word();
                String tag = word.tag();
                String assignedChunkTag = "";


                if (indexNPs < sizeOfExtractedNPsArray) {
                    BaseNounPhrase currentNP = extractedBaseNounPhrases.get(indexNPs);
                    String[] words = currentNP.getPhraseString().split(" ");
                    String currentWord = words[indexInNP];


                    if (token.equals(currentWord)) {
                        if (indexInNP == 0) {
                            assignedChunkTag = "B";
                        } else {
                            assignedChunkTag = "I";
                        }
                        if (indexInNP + 1 < words.length) {
                            indexInNP++;
                        } else {
                            indexNPs++;
                            indexInNP = 0;
                        }
                    } else {
                        assignedChunkTag = "O";
                    }
                    String line = token + "\t" + tag + "\t" + assignedChunkTag;
                    writer.println(line);
                } else break sentenceLoop;
            }
        }

        writer.close();

    }


    /**
     * Creates BaseNounPhrase Object by finding the start and end offset and the head
     * @param baseNP the string of the extracted baseNP
     * @param phraseStringWithPOSTags the string of the extracted baseNP with each POS appended to each token using /
     * @param taggedSentence the sentence the phrase was extracted from
     * @param POSTag the POS Tag of the last token
     * @return BaseNounPhrase Object with its offsets
     */
    private BaseNounPhrase createBaseNounPhrase(String baseNP, String phraseStringWithPOSTags, List<TaggedWord> taggedSentence, String POSTag) {

        int startOffset = -1;
        int endOffset = -1;

        String wordsInBaseNP[] = baseNP.split(" ");
        String firstWord = wordsInBaseNP[0];

        int baseNPLength = baseNP.length();

        for (TaggedWord currentWord : taggedSentence) {

            String cleanCurrentWord = currentWord.word()/*.replace("\\/", "//")*/;

            if (cleanCurrentWord.equals(firstWord)) { //Checks if word is the same as the first word of the baseNP
                if ((currentWord.beginPosition() > lastStartOffset) && startOffset < 0) { //Only sets startOffset if the word is after the beginning of the last baseNP and the startOffset has not been set yet
                    startOffset = currentWord.beginPosition();
                    endOffset = startOffset + baseNPLength - 1;
                    lastStartOffset = startOffset;
                    break;
                }
            }
        }

        if (endOffset == -1) {
            System.out.println("Something went wrong while finding the appropriate offsets.");
        }
        return new BaseNounPhrase(baseNP, phraseStringWithPOSTags, startOffset, endOffset, POSTag);
    }

    /**
     * Extracts the RegEx String from the rules files
     * Substitues all placeholders accordingly
     * @param rulesFilePath
     * @return Complete RegEx String
     * @throws IOException
     */
    private String getRegExString(String rulesFilePath) throws IOException {

        String rulesRegEx = "(";

        BufferedReader br = new BufferedReader(new FileReader(rulesFilePath));
        String currentLine;

        ArrayList<String> rules = new ArrayList<String>();
        HashMap<String, String> dictionaryOfRules = new HashMap<String, String>();

        Pattern ruleNamePattern = Pattern.compile("([^\\s]+)(?:[\\;]{2}(?=[^@]))");
        Matcher ruleNameMatcher = ruleNamePattern.matcher("");

        /**
         * Creates dictionary with rules and their names
         */

        while (null != (currentLine = br.readLine())) { //first rules in file are checked first
            if (!currentLine.substring(0, 1).equals("#")) {

                rules.add(currentLine);

                for (int i = 0; i < rules.size(); i++) {

                    String ruleLine = rules.get(i);
                    ruleNameMatcher.reset(ruleLine);

                    if (ruleNameMatcher.find()) {

                        String ruleName = ruleNameMatcher.group(0);
                        String rule = ruleLine.replaceAll(ruleName, "");

                        dictionaryOfRules.put(ruleName.replaceAll("\\;\\;", ""), rule);

                        rules.remove(i);
                        rules.add(i, rule);

                    }
                }
            }
        }

        /**
         * Replaces rule placeholders with the actual rules
         */
        Pattern ruleReplacementPattern = Pattern.compile(";@[^\\s]+?@;");
        Matcher ruleReplacementMatcher = ruleReplacementPattern.matcher("");

        for (int i = 0; i < rules.size(); i++) {

            String rule = rules.get(i);
            ruleReplacementMatcher.reset(rule);

            while (ruleReplacementMatcher.find()) {

                String ruleNameToken = ruleReplacementMatcher.group(0);
                String ruleName = ruleNameToken.replaceAll("(@;|;@)", "");

                String ruleToInsert = dictionaryOfRules.get(ruleName);

                rule = rule.replace(ruleNameToken, ruleToInsert);
                ruleReplacementMatcher.reset(rule);

            }

            /**
             * Creates final Regex
             */
            rule = "(" + rule + ")";
            if (!rulesRegEx.equals("(")) {
                rulesRegEx = rulesRegEx + "|" + rule;
            } else {
                rulesRegEx = rulesRegEx + rule;
            }
        }

        rulesRegEx = rulesRegEx + ")";
        return rulesRegEx;
    }

    /**
     * Extracts the List of Rejection Rules from the rejection rules files
     * Substitues all placeholders accordingly
     * Organizes Rules by their key/delimiter
     * @param rulesFilePath
     * @return List of RejectionRule Objects
     * @throws IOException
     */
    private ArrayList<RejectionRule> getRejectionRules(String rulesFilePath) throws IOException {
        BufferedReader br = new BufferedReader(new FileReader(rulesFilePath));
        String currentLine;

        ArrayList<RejectionRule> allRules = new ArrayList<RejectionRule>();
        RejectionRule rule = null;

        while (null != (currentLine = br.readLine())) { //first rules in file are checked first
            String firstCharacter = currentLine.substring(0, 1);
            if (!firstCharacter.equals("#")) {

                if (firstCharacter.equals("∞")) {
                    String key = currentLine.substring(1, currentLine.lastIndexOf("∞"));
                    String operation = currentLine.substring(currentLine.indexOf(";")+1, currentLine.lastIndexOf(";"));

                    rule = new RejectionRule(key, operation);
                    allRules.add(rule);
                }
                else if (rule != null) {
                    rule.addRule(currentLine);
                }

            }
        }
        return allRules;
    }
}