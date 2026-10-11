// Template Module - Web Interface Script
// Dit bestand bevat JavaScript logica voor je web interface

document.addEventListener('DOMContentLoaded', function() {
    console.log('Template Module Web Interface geladen');
    
    // Voorbeeld: Fetch data van PlugModCore API
    fetchModuleStatus();
});

/**
 * Voorbeeld functie om module status op te halen
 */
async function fetchModuleStatus() {
    try {
        // API endpoint - pas de module-id aan
        const response = await fetch('/api/modules');
        const data = await response.json();
        
        console.log('Module data:', data);
        // Doe iets met de data
        
    } catch (error) {
        console.error('Fout bij ophalen module data:', error);
    }
}

/**
 * Voorbeeld functie voor API call naar je module
 * Zorg dat je deze endpoints in je Java code implementeert
 */
async function callModuleAPI(endpoint, method = 'GET', body = null) {
    try {
        const options = {
            method: method,
            headers: {
                'Content-Type': 'application/json'
            }
        };
        
        if (body) {
            options.body = JSON.stringify(body);
        }
        
        const response = await fetch(`/api/modules/MODULE_ID/${endpoint}`, options);
        
        if (!response.ok) {
            throw new Error(`API Error: ${response.status}`);
        }
        
        return await response.json();
        
    } catch (error) {
        console.error('API Call fout:', error);
        return null;
    }
}

/**
 * Voorbeeld: Reload page content
 */
function reloadContent() {
    location.reload();
}
