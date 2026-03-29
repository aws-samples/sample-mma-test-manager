package com.mma.testmanager.controller;

import com.mma.testmanager.entity.KnowledgeBase;
import com.mma.testmanager.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;

@Controller
@RequestMapping("/knowledge-base")
@RequiredArgsConstructor
public class KnowledgeBaseController {
    private final KnowledgeBaseService knowledgeBaseService;
    
    @GetMapping
    public String list(@RequestParam(required = false) String search,
                      @RequestParam(required = false) String sourceDb,
                      @RequestParam(required = false) String targetDb,
                      Model model) {
        model.addAttribute("knowledgeBases", knowledgeBaseService.search(search, sourceDb, targetDb));
        model.addAttribute("search", search);
        model.addAttribute("sourceDb", sourceDb);
        model.addAttribute("targetDb", targetDb);
        return "knowledge-base/list";
    }
    
    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("kb", new KnowledgeBase());
        return "knowledge-base/form";
    }
    
    @GetMapping("/edit/{id}")
    public String editForm(@PathVariable Long id, Model model) {
        KnowledgeBase kb = knowledgeBaseService.findById(id)
            .orElseThrow(() -> new RuntimeException("Knowledge base not found"));
        model.addAttribute("kb", kb);
        return "knowledge-base/form";
    }
    
    @PostMapping("/save")
    public String save(@ModelAttribute KnowledgeBase kb) {
        if (kb.getId() == null) {
            kb.setCreatedAt(LocalDateTime.now());
        }
        kb.setUpdatedAt(LocalDateTime.now());
        knowledgeBaseService.save(kb);
        return "redirect:/knowledge-base";
    }
    
    @PostMapping("/delete/{id}")
    public String delete(@PathVariable Long id) {
        knowledgeBaseService.delete(id);
        return "redirect:/knowledge-base";
    }
}
