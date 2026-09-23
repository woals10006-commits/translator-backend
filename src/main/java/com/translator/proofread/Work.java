package com.translator.proofread;

/**
 * 작품(프로젝트) 하나. 담당자가 여러 소설을 동시에 맡기 때문에, 원고 폴더와
 * 프롬프트·용어집을 작품 단위로 묶어 둔다. 교정교열 요청 한 건에는 이 작품의
 * 정보만 실려 나가므로 다른 소설의 설정이 섞일 수 없다.
 */
public class Work {
    private String id;
    private String name;        // 작품명 — 프롬프트의 {{작품명}} 자리에 들어간다
    private String folder;      // 원고가 들어 있는 폴더. 결과 TXT도 여기 저장된다
    private String prompt;      // 이 작품에 쓸 교정교열 프롬프트(비어 있으면 공통 프롬프트)
    private String glossary;    // 인명·호칭·용어집. 매 화 요청에 함께 실린다
    private String issueWords;  // 한 줄에 하나씩. 교정 후 기계 검색으로 한 번 더 훑는다

    public String getId() { return id; }
    public void setId(String v) { id = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getFolder() { return folder; }
    public void setFolder(String v) { folder = v; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String v) { prompt = v; }
    public String getGlossary() { return glossary; }
    public void setGlossary(String v) { glossary = v; }
    public String getIssueWords() { return issueWords; }
    public void setIssueWords(String v) { issueWords = v; }
}
